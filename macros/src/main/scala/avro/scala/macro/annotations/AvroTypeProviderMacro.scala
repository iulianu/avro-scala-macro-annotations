package com.julianpeeters.avro.annotations

import provider._

import scala.reflect.macros.blackbox.Context
import scala.language.experimental.macros
import scala.annotation.StaticAnnotation

import collection.JavaConversions._
import java.io.File

import com.typesafe.scalalogging._

object AvroTypeProviderMacro extends LazyLogging {

  def impl(c: Context)(annottees: c.Expr[Any]*): c.Expr[Any] = {
    import c.universe._
    import Flag._

    val result = {
      annottees.map(_.tree).toList match {
        case q"$mods class $name[..$tparams](..$first)(...$rest) extends ..$parents { $self => ..$body }" :: tail => {

          // get the namespace from the context and passing it around instead of using schema.getNamespace
          // in order to read schemas that omit namespace (e.g. nested schemas or python's avrostorage default)
          val namespace = NamespaceProbe.getNamespace(c)

          val fullName: String = {
            if (namespace == null) name.toString
            else s"$namespace.$name"
          }

          // currently, having a `@AvroRecord` the only thing that will trigger the writing of vars instead of vals
          val isImmutable: Boolean = {
            !mods.annotations.exists(mod => mod.toString == "new AvroRecord()" | mod.toString =="new AvroRecord(null)")
          }

          // helpful for IDE users who may not be able to easily see where their files live
//          logger.info(s"Current path: ${new File(".").getAbsolutePath}")

          // get the schema for the record that this class represents
          val avroFilePath = FilePathProbe.getPath(c)
          val infile = new File(avroFilePath)
          val instream = this.getClass.getResourceAsStream(avroFilePath)
          val fileSchema = FileParser.getSchema(infile, instream)
          val nestedSchemas = NestedSchemaExtractor.getNestedSchemas(fileSchema)
          // first try matching schema record full name to class full name, then by the
          // regular name in case we're trying to read from a non-namespaced schema
          val classSchema = nestedSchemas.find(s => s.getFullName == fullName)
           .getOrElse(nestedSchemas.find(s => s.getName == name.toString && s.getNamespace == null)
             .getOrElse(sys.error("no record found with name " + name)))

          // wraps each schema field in a quasiquote, returning immutable val defs if immutable flag is true
          val newFields: List[ValDef] = ValDefGenerator.asScalaFields(classSchema, namespace, isImmutable, c)

          tail match {
            // if there is no preexisiting companion
            case Nil => q"$mods class $name[..$tparams](..${newFields:::first})(...$rest) extends ..$parents { $self => ..$body }"
            // if there is a preexisting companion, include it with the updated classDef
            case moduleDef @ q"object $moduleName { ..$moduleBody }" :: Nil => {
              q"""$mods class $name[..$tparams](..${newFields:::first})(...$rest) extends ..$parents { $self => ..$body };
                object ${name.toTermName} { ..$moduleBody }"""
            }
          }
        }
      }
    }

    c.Expr[Any](result)
  }
}

class AvroTypeProvider(inputPath: String) extends StaticAnnotation {
  def macroTransform(annottees: Any*): Any = macro AvroTypeProviderMacro.impl
}
