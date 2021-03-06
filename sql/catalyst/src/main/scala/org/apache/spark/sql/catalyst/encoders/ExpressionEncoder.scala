/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.catalyst.encoders

import scala.reflect.ClassTag
import scala.reflect.runtime.universe.{typeTag, TypeTag}

import org.apache.spark.sql.Encoder
import org.apache.spark.sql.catalyst.{InternalRow, JavaTypeInference, ScalaReflection}
import org.apache.spark.sql.catalyst.analysis.{Analyzer, GetColumnByOrdinal, SimpleAnalyzer, UnresolvedAttribute, UnresolvedExtractValue}
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.expressions.codegen.{GenerateSafeProjection, GenerateUnsafeProjection}
import org.apache.spark.sql.catalyst.expressions.objects.{AssertNotNull, Invoke, NewInstance}
import org.apache.spark.sql.catalyst.optimizer.SimplifyCasts
import org.apache.spark.sql.catalyst.plans.logical.{CatalystSerde, DeserializeToObject, LocalRelation}
import org.apache.spark.sql.types.{BooleanType, ObjectType, StructField, StructType}
import org.apache.spark.util.Utils

/**
 * A factory for constructing encoders that convert objects and primitives to and from the
 * internal row format using catalyst expressions and code generation.  By default, the
 * expressions used to retrieve values from an input row when producing an object will be created as
 * follows:
 *  - Classes will have their sub fields extracted by name using [[UnresolvedAttribute]] expressions
 *    and [[UnresolvedExtractValue]] expressions.
 *  - Tuples will have their subfields extracted by position using [[BoundReference]] expressions.
 *  - Primitives will have their values extracted from the first ordinal with a schema that defaults
 *    to the name `value`.
 */
object ExpressionEncoder {
  def apply[T : TypeTag](): ExpressionEncoder[T] = {
    // We convert the not-serializable TypeTag into StructType and ClassTag.
    val mirror = ScalaReflection.mirror
    val tpe = typeTag[T].in(mirror).tpe

    // 不支持Option[MyClass]这样的数据类型
    if (ScalaReflection.optionOfProductType(tpe)) {
      throw new UnsupportedOperationException(
        "Cannot create encoder for Option of Product type, because Product type is represented " +
          "as a row, and the entire row can not be null in Spark SQL like normal databases. " +
          "You can wrap your type with Tuple1 if you do want top level null Product objects, " +
          "e.g. instead of creating `Dataset[Option[MyClass]]`, you can do something like " +
          "`val ds: Dataset[Tuple1[MyClass]] = Seq(Tuple1(MyClass(...)), Tuple1(null)).toDS`")
    }

    val cls = mirror.runtimeClass(tpe)
    // 如果tpe是Product（case class）类型或DefinedByConstructorParams类型， 则flat = false，这意味
    // serializer.size > 1，且需要在下面添加AssertNotNull。反之，如果tpe为基础类型，e.g. int，long，
    // 则flat = true，此时serializer.size == 1，而且也不需要添加AssertNotNull，因为对于每一个基础类型，
    // 都有一个对应的默认值。
    val flat = !ScalaReflection.definedByConstructorParams(tpe)

    val inputObject = BoundReference(0, ScalaReflection.dataTypeFor[T], nullable = !cls.isPrimitive)
    val nullSafeInput = if (flat) {
      inputObject
    } else {
      // 如果inputObject是Product类型，则inputObject本身不能为null，但是inputObject的成员变量可以为null。
      // For input object of Product type, we can't encode it to row if it's null, as Spark SQL
      // doesn't allow top-level row to be null, only its columns can be null.
      AssertNotNull(inputObject, Seq("top level Product input object"))
    }
    // 注意serializer的类型是CreateNamedStruct（如果T是类型case class Person(name: String, age: Int),
    // 则CreateNamedStruct会包含Person的name、age字段的信息）
    val serializer = ScalaReflection.serializerFor[T](nullSafeInput)
    val deserializer = ScalaReflection.deserializerFor[T]

    val schema = serializer.dataType

    new ExpressionEncoder[T](
      schema,
      flat,
      serializer.flatten,
      deserializer,
      ClassTag[T](cls))
  }

  // TODO: improve error message for java bean encoder.
  def javaBean[T](beanClass: Class[T]): ExpressionEncoder[T] = {
    val schema = JavaTypeInference.inferDataType(beanClass)._1
    assert(schema.isInstanceOf[StructType])

    val serializer = JavaTypeInference.serializerFor(beanClass)
    val deserializer = JavaTypeInference.deserializerFor(beanClass)

    new ExpressionEncoder[T](
      schema.asInstanceOf[StructType],
      flat = false,
      serializer.flatten,
      deserializer,
      ClassTag[T](beanClass))
  }

  /**
   * Given a set of N encoders, constructs a new encoder that produce objects as items in an
   * N-tuple.  Note that these encoders should be unresolved so that information about
   * name/positional binding is preserved.
   */
  def tuple(encoders: Seq[ExpressionEncoder[_]]): ExpressionEncoder[_] = {
    encoders.foreach(_.assertUnresolved())

    val schema = StructType(encoders.zipWithIndex.map {
      case (e, i) =>
        val (dataType, nullable) = if (e.flat) {
          e.schema.head.dataType -> e.schema.head.nullable
        } else {
          e.schema -> true
        }
        StructField(s"_${i + 1}", dataType, nullable)
    })

    val cls = Utils.getContextOrSparkClassLoader.loadClass(s"scala.Tuple${encoders.size}")

    val serializer = encoders.zipWithIndex.map { case (enc, index) =>
      val originalInputObject = enc.serializer.head.collect { case b: BoundReference => b }.head
      val newInputObject = Invoke(
        BoundReference(0, ObjectType(cls), nullable = true),
        s"_${index + 1}",
        originalInputObject.dataType)

      val newSerializer = enc.serializer.map(_.transformUp {
        case b: BoundReference if b == originalInputObject => newInputObject
      })

      if (enc.flat) {
        newSerializer.head
      } else {
        // For non-flat encoder, the input object is not top level anymore after being combined to
        // a tuple encoder, thus it can be null and we should wrap the `CreateStruct` with `If` and
        // null check to handle null case correctly.
        // e.g. for Encoder[(Int, String)], the serializer expressions will create 2 columns, and is
        // not able to handle the case when the input tuple is null. This is not a problem as there
        // is a check to make sure the input object won't be null. However, if this encoder is used
        // to create a bigger tuple encoder, the original input object becomes a filed of the new
        // input tuple and can be null. So instead of creating a struct directly here, we should add
        // a null/None check and return a null struct if the null/None check fails.
        val struct = CreateStruct(newSerializer)
        val nullCheck = Or(
          IsNull(newInputObject),
          Invoke(Literal.fromObject(None), "equals", BooleanType, newInputObject :: Nil))
        If(nullCheck, Literal.create(null, struct.dataType), struct)
      }
    }

    val childrenDeserializers = encoders.zipWithIndex.map { case (enc, index) =>
      if (enc.flat) {
        enc.deserializer.transform {
          case g: GetColumnByOrdinal => g.copy(ordinal = index)
        }
      } else {
        val input = GetColumnByOrdinal(index, enc.schema)
        val deserialized = enc.deserializer.transformUp {
          case UnresolvedAttribute(nameParts) =>
            assert(nameParts.length == 1)
            UnresolvedExtractValue(input, Literal(nameParts.head))
          case GetColumnByOrdinal(ordinal, _) => GetStructField(input, ordinal)
        }
        If(IsNull(input), Literal.create(null, deserialized.dataType), deserialized)
      }
    }

    val deserializer =
      NewInstance(cls, childrenDeserializers, ObjectType(cls), propagateNull = false)

    new ExpressionEncoder[Any](
      schema,
      flat = false,
      serializer,
      deserializer,
      ClassTag(cls))
  }

  // Tuple1
  def tuple[T](e: ExpressionEncoder[T]): ExpressionEncoder[Tuple1[T]] =
    tuple(Seq(e)).asInstanceOf[ExpressionEncoder[Tuple1[T]]]

  def tuple[T1, T2](
      e1: ExpressionEncoder[T1],
      e2: ExpressionEncoder[T2]): ExpressionEncoder[(T1, T2)] =
    tuple(Seq(e1, e2)).asInstanceOf[ExpressionEncoder[(T1, T2)]]

  def tuple[T1, T2, T3](
      e1: ExpressionEncoder[T1],
      e2: ExpressionEncoder[T2],
      e3: ExpressionEncoder[T3]): ExpressionEncoder[(T1, T2, T3)] =
    tuple(Seq(e1, e2, e3)).asInstanceOf[ExpressionEncoder[(T1, T2, T3)]]

  def tuple[T1, T2, T3, T4](
      e1: ExpressionEncoder[T1],
      e2: ExpressionEncoder[T2],
      e3: ExpressionEncoder[T3],
      e4: ExpressionEncoder[T4]): ExpressionEncoder[(T1, T2, T3, T4)] =
    tuple(Seq(e1, e2, e3, e4)).asInstanceOf[ExpressionEncoder[(T1, T2, T3, T4)]]

  def tuple[T1, T2, T3, T4, T5](
      e1: ExpressionEncoder[T1],
      e2: ExpressionEncoder[T2],
      e3: ExpressionEncoder[T3],
      e4: ExpressionEncoder[T4],
      e5: ExpressionEncoder[T5]): ExpressionEncoder[(T1, T2, T3, T4, T5)] =
    tuple(Seq(e1, e2, e3, e4, e5)).asInstanceOf[ExpressionEncoder[(T1, T2, T3, T4, T5)]]
}

/**
 * A generic encoder for JVM objects that uses Catalyst Expressions for a `serializer`
 * and a `deserializer`.
 *
 * @param schema The schema after converting `T` to a Spark SQL row.
 * @param serializer A set of expressions, one for each top-level field that can be used to
 *                   extract the values from a raw object into an [[InternalRow]].
 * @param deserializer An expression that will construct an object given an [[InternalRow]].
 * @param clsTag A classtag for `T`.
 */
case class ExpressionEncoder[T](
    schema: StructType,
    flat: Boolean,
    serializer: Seq[Expression],
    deserializer: Expression,
    clsTag: ClassTag[T])
  extends Encoder[T] {

  // QUESTION: flat？
  // ANSWER: 如果是java的基础类型，e.g. int,long, 则flat = true，此时， serializer.size = 1；
  // 而如果是Product或DefinedByConstructorParams，e.g. case class Person(name: String, age Int)，
  // 则serializer.size > 1。因为name，age这两个字段都会有各自的serializer。
  if (flat) require(serializer.size == 1)

  // serializer expressions are used to encode an object to a row, while the object is usually an
  // intermediate value produced inside an operator, not from the output of the child operator. This
  // is quite different from normal expressions, and `AttributeReference` doesn't work here
  // (intermediate value is not an attribute). We assume that all serializer expressions use the
  // same `BoundReference` to refer to the object, and throw exception if they don't.
  assert(serializer.forall(_.references.isEmpty), "serializer cannot reference any attributes.")
  assert(serializer.flatMap { ser =>
    val boundRefs = ser.collect { case b: BoundReference => b }
    assert(boundRefs.nonEmpty,
      "each serializer expression should contain at least one `BoundReference`")
    boundRefs
  }.distinct.length <= 1, "all serializer expressions must use the same BoundReference.")

  /**
   * Returns a new copy of this encoder, where the `deserializer` is resolved and bound to the
   * given schema.
   *
   * Note that, ideally encoder is used as a container of serde expressions, the resolution and
   * binding stuff should happen inside query framework.  However, in some cases we need to
   * use encoder as a function to do serialization directly(e.g. Dataset.collect), then we can use
   * this method to do resolution and binding outside of query framework.
   */
  def resolveAndBind(
      attrs: Seq[Attribute] = schema.toAttributes,
      analyzer: Analyzer = SimpleAnalyzer): ExpressionEncoder[T] = {
    // 从这个plan的LocalRelation可以看到，它是没有数据的，也没有其它的operator。可能这就是所谓的dummyPlan吧。
    val dummyPlan = CatalystSerde.deserialize(LocalRelation(attrs))(this)
    val analyzedPlan = analyzer.execute(dummyPlan)
    analyzer.checkAnalysis(analyzedPlan)
    // SimplifyCasts:去除必要的Cast
    val resolved = SimplifyCasts(analyzedPlan).asInstanceOf[DeserializeToObject].deserializer
    val bound = BindReferences.bindReference(resolved, attrs)
    copy(deserializer = bound)
  }

  @transient
  private lazy val extractProjection = GenerateUnsafeProjection.generate(serializer)

  @transient
  private lazy val inputRow = new GenericInternalRow(1)

  @transient
  private lazy val constructProjection = GenerateSafeProjection.generate(deserializer :: Nil)

  /**
   * Returns a new set (with unique ids) of [[NamedExpression]] that represent the serialized form
   * of this object.
   */
  def namedExpressions: Seq[NamedExpression] = schema.map(_.name).zip(serializer).map {
    case (_, ne: NamedExpression) => ne.newInstance()
    case (name, e) => Alias(e, name)()
  }

  /**
   * 返回对象`t`的编码版本，即Spark SQL row的形式。注意：对于同一个对象`t`多次调用toRow()是允许的，且需要返回
   * 相同的InternalRow对象。因此，调用者需要在发起另一次调用之前，拷贝当前的结果对象。（否则，如果有多个线程操作
   * 该相同的row，该row的状态会发生变化，导致不同的线程无法维护各自的读写状态。）
   * Returns an encoded version of `t` as a Spark SQL row.  Note that multiple calls to
   * toRow are allowed to return the same actual [[InternalRow]] object. Thus, the caller should
   * copy the result before making another call if required.
   */
  def toRow(t: T): InternalRow = try {
    inputRow(0) = t
    // 请看GenerateUnsafeProjection#create()里gencode。因为extractProjection是通过gencode生成的，所以我们在idea里
    // 无法直接查看它的代码，只能看到通过gencode生成的字符串代码。最后能够通过该方法中的logDebug，查看一下完整的
    // 生成的代码，更有助于理解。
    extractProjection(inputRow)
  } catch {
    case e: Exception =>
      throw new RuntimeException(
        s"Error while encoding: $e\n${serializer.map(_.simpleString).mkString("\n")}", e)
  }

  /**
   * Returns an object of type `T`, extracting the required values from the provided row.  Note that
   * you must `resolveAndBind` an encoder to a specific schema before you can call this
   * function.
   */
  def fromRow(row: InternalRow): T = try {
    constructProjection(row).get(0, ObjectType(clsTag.runtimeClass)).asInstanceOf[T]
  } catch {
    case e: Exception =>
      throw new RuntimeException(s"Error while decoding: $e\n${deserializer.simpleString}", e)
  }

  /**
   * The process of resolution to a given schema throws away information about where a given field
   * is being bound by ordinal instead of by name.  This method checks to make sure this process
   * has not been done already in places where we plan to do later composition of encoders.
   */
  def assertUnresolved(): Unit = {
    // TODO read 啥意思啊？看不懂
    (deserializer +:  serializer).foreach(_.foreach {
      case a: AttributeReference if a.name != "loopVar" =>
        sys.error(s"Unresolved encoder expected, but $a was found.")
      case _ =>
    })
  }

  protected val attrs = serializer.flatMap(_.collect {
    case _: UnresolvedAttribute => ""
    case a: Attribute => s"#${a.exprId}"
    case b: BoundReference => s"[${b.ordinal}]"
  })

  protected val schemaString =
    schema
      .zip(attrs)
      .map { case(f, a) => s"${f.name}$a: ${f.dataType.simpleString}"}.mkString(", ")

  override def toString: String = s"class[$schemaString]"
}
