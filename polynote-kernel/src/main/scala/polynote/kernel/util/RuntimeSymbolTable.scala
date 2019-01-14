package polynote.kernel.util

import cats.effect.concurrent.Deferred
import cats.effect.{ContextShift, IO}
import fs2.Stream
import fs2.concurrent.Topic
import polynote.kernel.{KernelStatusUpdate, SymbolInfo, UpdatedSymbols}
import polynote.kernel.lang.LanguageKernel

import scala.collection.mutable
import scala.tools.nsc.interactive.Global

final class RuntimeSymbolTable(
  val global: Global,
  val classLoader: ClassLoader,
  statusUpdates: Topic[IO, KernelStatusUpdate])(implicit
  contextShift: ContextShift[IO]
) extends Serializable {
  import global.{Type, TermName, Symbol}

  private val currentSymbolTable: mutable.HashMap[TermName, RuntimeValue] = new mutable.HashMap()
  private val disposed = ReadySignal()

  private val runtimeMirror = scala.reflect.runtime.universe.runtimeMirror(classLoader)
  private val importFromRuntime = global.internal.createImporter(scala.reflect.runtime.universe)

  private val cellIds: mutable.TreeSet[String] = new mutable.TreeSet()

  private def typeOf(value: Any, staticType: Option[Type]): Type = staticType.getOrElse {
    try {
      importFromRuntime.importType {
        runtimeMirror.reflect(value).symbol.asType.toType
      }
    } catch {
      case err: Throwable => global.NoType
    }
  }

  private val newSymbols: Topic[IO, RuntimeValue] =
    Topic[IO, RuntimeValue](
      RuntimeValue(TermName("kernel"), polynote.runtime.Runtime, global.typeOf[polynote.runtime.Runtime.type], None, "$Predef")
    ).unsafeRunSync()

  def currentTerms: Seq[RuntimeValue] = currentSymbolTable.values.toSeq

  def subscribe(maxQueued: Int = 32): Stream[IO, RuntimeValue] = newSymbols.subscribe(maxQueued).interruptWhen(disposed())

  private def putValue(value: RuntimeValue): Unit = {
    currentSymbolTable.put(value.name, value)
    polynote.runtime.Runtime.putValue(value.name.toString, value.value)

    cellIds.add(value.sourceCellId)
  }

  def publish(source: LanguageKernel[IO], sourceCellId: String)(name: TermName, value: Any, staticType: Option[global.Type]): IO[Unit] = {
    val rv = RuntimeValue(name, value, typeOf(value, staticType), Some(source), sourceCellId)
    IO(putValue(rv)).flatMap {
      _ => newSymbols.publish1(rv).flatMap {
        _ =>
          statusUpdates.publish1(UpdatedSymbols(SymbolInfo(name.decodedName.toString, rv.typeString, rv.valueString, Nil) :: Nil, Nil))
      }
    }
  }

  def publishAll(values: List[RuntimeValue]): IO[Unit] = {
    IO {
      values.foreach {
        rv =>
          putValue(rv)
      }
    }.flatMap {
      _ => statusUpdates.publish1(UpdatedSymbols(
        values.map(rv => SymbolInfo(rv.name.decodedName.toString, rv.typeString, rv.valueString, Nil)), Nil
      ))
    }
  }

  def close(): Unit = disposed.completeSync()

  def formatType(typ: global.Type): String = typ match {
    case mt @ global.MethodType(params: List[Symbol], result: Type) =>
      val paramStr = params.map {
        sym => s"${sym.nameString}: ${formatType(sym.typeSignatureIn(mt))}"
      }.mkString(", ")
      val resultType = formatType(result)
      s"($paramStr) => $resultType"
    case global.NoType => "<Unknown>"
    case _ =>
      val typName = typ.typeSymbolDirect.name
      val typNameStr = typ.typeSymbolDirect.nameString
      typ.typeArgs.map(formatType) match {
        case Nil => typNameStr
        case a if typNameStr == "<byname>" => s"=> $a"
        case a :: b :: Nil if typName.isOperatorName => s"$a $typNameStr $b"
        case a :: b :: Nil if typ.typeSymbol.owner.nameString == "scala" && (typNameStr == "Function1") =>
          s"$a => $b"
        case args if typ.typeSymbol.owner.nameString == "scala" && (typNameStr startsWith "Function") =>
          s"(${args.dropRight(1).mkString(",")}) => ${args.last}"
        case args => s"$typName[${args.mkString(", ")}]"
      }
  }

  sealed case class RuntimeValue(
    name: TermName,
    value: Any,
    scalaType: global.Type,
    source: Option[LanguageKernel[IO]],
    sourceCellId: String
  ) extends SymbolDecl[IO, global.type] {
    lazy val typeString: String = formatType(scalaType)
    lazy val valueString: String = value.toString match {
      case str if str.length > 64 => str.substring(0, 64)
      case str => str
    }

    // don't need to hash everything to determine hash code; name collisions are less common than hash comparisons
    override def hashCode(): Int = name.hashCode()
  }

  object RuntimeValue {
    def apply(name: String, value: Any, source: Option[LanguageKernel[IO]], sourceCell: String): RuntimeValue = RuntimeValue(
      global.TermName(name), value, typeOf(value, None), source, sourceCell
    )
  }
}

/**
  * A symbol defined in a notebook cell
  */
trait SymbolDecl[F[_], G <: Global] {
  def name: G#TermName
  def source: Option[LanguageKernel[F]]
  def sourceCellId: String
  def scalaType: G#Type
}