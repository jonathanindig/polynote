package polynote.server.repository.ipynb

import io.circe.{Decoder, Encoder, Json, JsonObject}
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import io.circe.syntax._
import polynote.data.Rope
import polynote.kernel.RuntimeError.RecoveredException
import polynote.kernel.{CompileErrors, Output, Result, RuntimeError}
import polynote.messages.{CellMetadata, Notebook, NotebookCell, NotebookConfig, ShortList, ShortString}

sealed trait JupyterCellType

case object Markdown extends JupyterCellType
case object Code extends JupyterCellType

object JupyterCellType {
  implicit val encoder: Encoder[JupyterCellType] = Encoder.encodeString.contramap {
    case Markdown => "markdown"
    case Code => "code"
  }

  implicit val decoder: Decoder[JupyterCellType] = Decoder.decodeString.emap {
    case "markdown" => Right(Markdown)
    case "code"     => Right(Code)
    case other      => Left(s"Invalid cell type $other")
  }
}

sealed trait JupyterOutput

object JupyterOutput {

  final case class Stream(
    name: String,
    text: List[String]
  ) extends JupyterOutput

  final case class DisplayData(
    data: Map[String, Json],
    metadata: Option[JsonObject]
  ) extends JupyterOutput

  final case class ExecuteResult(
    execution_count: Int,
    data: Map[String, Json],
    metadata: Option[JsonObject]
  ) extends JupyterOutput

  final case class Error(
    ename: String,
    evalue: String,
    traceback: List[String]
  ) extends JupyterOutput

  import io.circe.generic.extras, extras.Configuration
  implicit val conf: Configuration = Configuration.default.withDiscriminator("output_type").withSnakeCaseConstructorNames.withSnakeCaseMemberNames

  implicit val encoder: Encoder[JupyterOutput] = extras.semiauto.deriveEncoder[JupyterOutput]
  implicit val decoder: Decoder[JupyterOutput] = extras.semiauto.deriveDecoder[JupyterOutput]

  def toResult(result: JupyterOutput): Result = {
    def jsonToStr(json: Json): String = json.fold("", _.toString, _.toString, _.toString, _.map(jsonToStr).mkString, _.toString)

    def convertData(data: Map[String, Json], metadata: Option[JsonObject]) = metadata.flatMap(_("rel").flatMap(_.asString)) match {
      case Some("compiler_errors") => data("application/json").as[CompileErrors].fold(_ => CompileErrors(Nil), identity)
      case rel =>
        val relStr = rel.map(r => s"; rel=$r").getOrElse("")
        data.head match {
          case (mime, content) => Output(s"$mime$relStr", jsonToStr(content))
        }
    }

    result match {
      case Stream(name, text) => Output(s"text/plain; rel=$name", text.mkString)
      case DisplayData(data, metadata) => convertData(data, metadata)
      case ExecuteResult(_, data, metadata) => convertData(data, metadata)
      case Error(typ, message, trace) =>
        RuntimeError(RecoveredException(message, typ))
    }
  }

  def fromResult(result: Result, execId: Int): JupyterOutput = result match {
    case Output(contentType, content) =>
      val (mime, args) = Output.parseContentType(contentType)

      args.get("rel") match {
        case Some(name) if mime == "text/plain" => Stream(name, content.linesWithSeparators.toList)
        case Some("output") => DisplayData(Map(mime -> Json.arr(content.linesWithSeparators.toSeq.map(_.asJson): _*)), None)
        case None => ExecuteResult(execId, Map(mime -> Json.arr(content.linesWithSeparators.toSeq.map(_.asJson): _*)), None)
      }

    case e @ CompileErrors(errs) =>
      ExecuteResult(
        execId,
        Map(
          "application/json" -> e.asJson,
          "text/plain" -> Json.arr(errs.map(_.toString).map(Json.fromString): _*)),
        Some(JsonObject("rel" -> Json.fromString("compiler_errors"))))

    case e @ RuntimeError(err) =>
      val (typ, msg) = err match {
        case RecoveredException(msg, typ) => (typ, msg)
        case err => (err.getClass.getName, err.getMessage)
      }
      Error(typ, Option(msg).getOrElse(""), Nil)
  }
}

final case class JupyterCell(
  cell_type: JupyterCellType,
  execution_count: Option[Int],
  metadata: Option[JsonObject],
  language: Option[String],
  source: List[String],
  outputs: Option[List[JupyterOutput]]
)

object JupyterCell {
  implicit val encoder: Encoder[JupyterCell] = deriveEncoder[JupyterCell]
  implicit val decoder: Decoder[JupyterCell] = deriveDecoder[JupyterCell]

  def toNotebookCell(cell: JupyterCell, index: Int): NotebookCell = {
    val language = cell.cell_type match {
      case Markdown => "text"
      case Code     => cell.language.getOrElse("scala")
    }

    val id = cell.metadata.flatMap(_("name").flatMap(_.asString)).getOrElse(s"Cell${cell.execution_count.getOrElse(index)}")

    val meta = cell.metadata.map {
      obj =>
        val disabled = obj("cell.metadata.run_control.frozen").flatMap(_.asBoolean).getOrElse(false)
        val hideSource = obj("jupyter.source_hidden").flatMap(_.asBoolean).getOrElse(false)
        val hideOutput = obj("jupyter.outputs_hidden").flatMap(_.asBoolean).getOrElse(false)
        CellMetadata(disabled, hideSource, hideOutput)
    }.getOrElse(CellMetadata())

    NotebookCell(id, language, Rope(cell.source.mkString), ShortList(cell.outputs.getOrElse(Nil).map(JupyterOutput.toResult)), meta)
  }

  private val CellIdPattern = """Cell(\d+)""".r

  def fromNotebookCell(cell: NotebookCell): JupyterCell = {
    val executionCount = cell.id match {
      case CellIdPattern(id) => try Some(id.toInt) catch { case err: Throwable => None }
      case _ => None
    }

    val contentLines = cell.content.toString.linesWithSeparators.toList
    val cellType = cell.language.toString match {
      case "text" | "markdown" => Markdown
      case _ => Code
    }

    val meta = cell.metadata match {
      case CellMetadata(false, false, false) => None
      case meta => Some {
        JsonObject.fromMap(List(
          "cell.metadata.run_control.frozen" -> meta.disableRun,
          "jupyter.source_hidden" -> meta.hideSource,
          "jupyter.outputs_hidden" -> meta.hideOutput).filter(_._2).toMap.mapValues(Json.fromBoolean))
      }
    }

    val outputs = cell.results.map(JupyterOutput.fromResult(_, executionCount.getOrElse(-1)))

    JupyterCell(cellType, executionCount, meta, Some(cell.language), contentLines, Some(outputs))
  }
}

final case class JupyterNotebook(
  metadata: Option[JsonObject],
  nbformat: Int = 4,
  nbformat_minor: Int = 0,
  cells: List[JupyterCell]
)

object JupyterNotebook {
  implicit val encoder: Encoder[JupyterNotebook] = deriveEncoder[JupyterNotebook]
  implicit val decoder: Decoder[JupyterNotebook] = deriveDecoder[JupyterNotebook]

  def toNotebook(path: String, notebook: JupyterNotebook): Notebook = {
    val config = notebook.metadata.flatMap(_("config")).flatMap(_.as[NotebookConfig].right.toOption)
    val cells = ShortList(notebook.cells.zipWithIndex.map((JupyterCell.toNotebookCell _).tupled))
    Notebook(ShortString(path), cells, config)
  }

  def fromNotebook(notebook: Notebook): JupyterNotebook = {
    val meta = JsonObject("config" -> notebook.config.map(_.asJson).getOrElse(Json.Null))
    val cells = notebook.cells.map(JupyterCell.fromNotebookCell)
    JupyterNotebook(metadata = Some(meta), cells = cells)
  }
}

final case class JupyterWorksheetV3(
  cells: List[JupyterCell],
  metadata: Option[JsonObject]
)

object JupyterWorksheetV3 {
  // some JSON transformations to nbformat 3
  implicit val encoder: Encoder[JupyterWorksheetV3] = deriveEncoder[JupyterWorksheetV3].mapJsonObject {
    obj =>
      val transformedCells = for {
        cellsJson <- obj("cells")
        cellsList <- cellsJson.asArray
      } yield for {
        cellJson <- cellsList
      } yield cellJson.mapObject {
        cellObj => JsonObject.fromMap {
          cellObj.toMap.map {
            case ("source", source) => ("input", source)
            case ("execution_id", id) => ("prompt_number", id)
            case ("outputs", outputs) => "outputs" -> outputs.mapArray {
              _.map {
                _.mapObject {
                  outputObj =>
                    val typ = outputObj("output_type").getOrElse("")
                    JsonObject.fromMap {
                      outputObj.toMap.map {
                        case ("name", name) if typ == "stream" => ("stream", name)
                        case ("execution_id", id) => ("prompt_number", id)
                        case ("data", data) => "data" -> data.mapObject {
                          dataObj => JsonObject.fromMap{
                            dataObj.toMap.map {
                              case ("application/json", json) => ("application/json", json.noSpaces.asJson)
                              case kv => kv
                            }
                          }
                        }
                        case kv => kv
                      }
                    }
                }
              }
            }
            case kv => kv
          }
        }
      }
      obj.remove("cells").add("cells", transformedCells.map(v => Json.arr(v: _*)).getOrElse(Json.Null))
  }

  implicit val decoder: Decoder[JupyterWorksheetV3] = deriveDecoder[JupyterWorksheetV3].prepare {
    cursor =>
      cursor.downField("cells").downArray.withFocus {
        _.mapArray {
          cells => cells.map {
            cell => cell.mapObject {
              cellObj => JsonObject.fromMap {
                cellObj.toMap.map {
                  case ("input", source) => ("source", source)
                  case ("prompt_number", id) => ("execution_id", id)
                  case ("outputs", outputs) => "outputs" -> outputs.mapArray {
                    _.map {
                      _.mapObject {
                        outputObj => JsonObject.fromMap{
                          outputObj.toMap.map {
                            case ("stream", name) => ("name", name)
                            case ("prompt_number", id) => ("execution_id", id)
                            case ("data", data) => "data" -> data.mapObject {
                              dataObj => JsonObject.fromMap {
                                dataObj.toMap.map {
                                  case ("application/json", jsonStr) => "application/json" -> io.circe.parser.parse(jsonStr.asString.getOrElse("")).right.getOrElse(jsonStr)
                                  case kv => kv
                                }
                              }
                            }
                            case kv => kv
                          }
                        }
                      }
                    }
                  }
                  case kv => kv
                }
              }
            }
          }
        }
      }.up.up
  }
}

final case class JupyterNotebookV3(
  metadata: Option[JsonObject],
  nbformat: Int = 3,
  nbformat_minor: Int = 0,
  worksheets: List[JupyterWorksheetV3]
)

object JupyterNotebookV3 {
  implicit val encoder: Encoder[JupyterNotebookV3] = deriveEncoder[JupyterNotebookV3]
  implicit val decoder: Decoder[JupyterNotebookV3] = deriveDecoder[JupyterNotebookV3]

  def fromV4(v4: JupyterNotebook): JupyterNotebookV3 = v4 match {
    case JupyterNotebook(metadata, _, _, cells) => JupyterNotebookV3(metadata = metadata, worksheets = List(JupyterWorksheetV3(cells, None)))
  }

  def toV4(v3: JupyterNotebookV3): JupyterNotebook = v3 match {
    case JupyterNotebookV3(metadata, _, _, worksheets) => JupyterNotebook(metadata = metadata, cells = worksheets.flatMap(_.cells))
  }
}

final case class JupyterNotebookStaged(
  metadata: Option[JsonObject],
  nbformat: Int,
  nbformat_minor: Int
)

object JupyterNotebookStaged {
  implicit val decoder: Decoder[JupyterNotebookStaged] = deriveDecoder[JupyterNotebookStaged]
}