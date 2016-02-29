/*
 * scala-exercises-exercise-compiler
 * Copyright (C) 2015-2016 47 Degrees, LLC. <http://www.47deg.com>
 */

package com.fortysevendeg.exercises
package compiler

import scala.reflect.api.Universe
import scala.reflect.runtime.{ universe ⇒ ru }
import scala.reflect.internal.util.BatchSourceFile

import cats.data.Xor
import cats.std.all._
import cats.syntax.flatMap._
import cats.syntax.traverse._

class CompilerJava {
  def compile(library: AnyRef, sources: Array[String], targetPackage: String): Array[String] = {
    Compiler().compile(library.asInstanceOf[exercise.Library], sources.toList, targetPackage)
      .fold(`🍺` ⇒ throw new Exception(`🍺`), out ⇒ Array(out._1, out._2))
  }
}

case class Compiler() {
  lazy val sourceTextExtractor = new SourceTextExtraction()

  def compile(library: exercise.Library, sources: List[String], targetPackage: String) = {

    val mirror = ru.runtimeMirror(library.getClass.getClassLoader)
    import mirror.universe._

    val internal = CompilerInternal(mirror, sourceTextExtractor.extractAll(sources))

    case class LibraryInfo(
      symbol:   ClassSymbol,
      comment:  CommentRendering.RenderedComment.Library,
      sections: List[SectionInfo],
      // TODO: consider deriving color from a comment param
      color: Option[String]
    )

    case class SectionInfo(
      symbol:    ClassSymbol,
      comment:   CommentRendering.RenderedComment.Section,
      exercises: List[ExerciseInfo]
    )

    case class ExerciseInfo(
      symbol:          MethodSymbol,
      comment:         CommentRendering.RenderedComment.Exercise,
      code:            String,
      qualifiedMethod: String
    )

    def enhanceDocError(symbol: Symbol)(error: String) =
      s"""$error in ${internal.symbolToPath(symbol).mkString(".")}"""

    def maybeMakeLibraryInfo(
      library: exercise.Library
    ) = for {
      symbol ← internal.instanceToClassSymbol(library)
      comment ← (internal.resolveComment(symbol) >>= Comments.parseAndRender[Comments.ParseMode.Library])
        .leftMap(enhanceDocError(symbol))
      sections ← library.sections.toList
        .map(internal.instanceToClassSymbol(_) >>= maybeMakeSectionInfo)
        .sequenceU
    } yield LibraryInfo(
      symbol = symbol,
      comment = comment,
      sections = sections,
      color = library.color
    )

    def maybeMakeSectionInfo(
      symbol: ClassSymbol
    ) = for {
      comment ← (internal.resolveComment(symbol) >>= Comments.parseAndRender[Comments.ParseMode.Section])
        .leftMap(enhanceDocError(symbol))
      exercises ← symbol.toType.decls.toList
        .filter(symbol ⇒
          symbol.isPublic && !symbol.isSynthetic &&
            symbol.name != termNames.CONSTRUCTOR && symbol.isMethod)
        .map(_.asMethod)
        .filterNot(_.isGetter)
        .map(maybeMakeExerciseInfo)
        .sequenceU
    } yield SectionInfo(
      symbol = symbol,
      comment = comment,
      exercises = exercises
    )

    def maybeMakeExerciseInfo(
      symbol: MethodSymbol
    ) = for {
      comment ← (internal.resolveComment(symbol) >>= Comments.parseAndRender[Comments.ParseMode.Exercise])
        .leftMap(enhanceDocError(symbol))
      code ← internal.resolveMethodBody(symbol)
    } yield ExerciseInfo(
      symbol = symbol,
      comment = comment,
      code = code,
      qualifiedMethod = internal.symbolToPath(symbol).mkString(".")
    )

    def oneline(msg: String) = {
      val msg0 = msg.lines.mkString(s"${Console.BLUE}\\n${Console.RESET}")
      // there's a chance that we could put elipses over part of the escaped
      // newline sequence... but oh well
      if (msg0.length <= 100) msg0
      else s"${msg0.take(97)}${Console.BLUE}...${Console.RESET}"
    }

    def dump(libraryInfo: LibraryInfo) {
      println(s"Found library ${libraryInfo.comment.name}")
      println(s" description: ${oneline(libraryInfo.comment.description)}")
      libraryInfo.sections.foreach { sectionInfo ⇒
        println(s" with section ${sectionInfo.comment.name}")
        println(s"  description: ${sectionInfo.comment.description.map(oneline)}")
        sectionInfo.exercises.foreach { exerciseInfo ⇒
          println(s"  with exercise ${exerciseInfo.symbol}")
          println(s"   description: ${exerciseInfo.comment.description.map(oneline)}")
        }
      }
    }

    // leaving this around, for debugging
    def debugDump(libraryInfo: LibraryInfo) {
      println("~ library")
      println(s" • symbol        ${libraryInfo.symbol}")
      println(s" - name          ${libraryInfo.comment.name}")
      println(s" - description   ${oneline(libraryInfo.comment.description)}")
      libraryInfo.sections.foreach { sectionInfo ⇒
        println(" ~ section")
        println(s"  • symbol        ${sectionInfo.symbol}")
        println(s"  - name          ${sectionInfo.comment.name}")
        println(s"  - description   ${sectionInfo.comment.description.map(oneline)}")
        sectionInfo.exercises.foreach { exerciseInfo ⇒
          println("  ~ exercise")
          println(s"   • symbol        ${exerciseInfo.symbol}")
          println(s"   - description   ${exerciseInfo.comment.description.map(oneline)}")
        }
      }
    }

    val treeGen = TreeGen[mirror.universe.type](mirror.universe)

    def generateTree(libraryInfo: LibraryInfo): (TermName, Tree) = {

      val (sectionTerms, sectionAndExerciseTrees) =
        libraryInfo.sections.map { sectionInfo ⇒
          val (exerciseTerms, exerciseTrees) =
            sectionInfo.exercises.map { exerciseInfo ⇒
              treeGen.makeExercise(
                name = internal.unapplyRawName(exerciseInfo.symbol.name),
                description = exerciseInfo.comment.description,
                code = exerciseInfo.code,
                qualifiedMethod = exerciseInfo.qualifiedMethod,
                explanation = exerciseInfo.comment.explanation
              )
            }.unzip

          val (sectionTerm, sectionTree) =
            treeGen.makeSection(
              name = sectionInfo.comment.name,
              description = sectionInfo.comment.description,
              exerciseTerms = exerciseTerms
            )

          (sectionTerm, sectionTree :: exerciseTrees)
        }.unzip

      val (libraryTerm, libraryTree) = treeGen.makeLibrary(
        name = libraryInfo.comment.name,
        description = libraryInfo.comment.description,
        color = libraryInfo.color,
        sectionTerms = sectionTerms
      )

      libraryTerm → treeGen.makePackage(
        packageName = targetPackage,
        trees = libraryTree :: sectionAndExerciseTrees.flatten
      )

    }

    maybeMakeLibraryInfo(library)
      .map { info ⇒
        dump(info)
        info
      }
      .map(generateTree)
      .map { case (TermName(kname), v) ⇒ s"$targetPackage.$kname" → showCode(v) }

  }

  private case class CompilerInternal(
      mirror:          ru.Mirror,
      sourceExtracted: SourceTextExtraction#Extracted
  ) {
    import mirror.universe._

    def instanceToClassSymbol(instance: AnyRef) =
      Xor.catchNonFatal(mirror.classSymbol(instance.getClass))
        .leftMap(e ⇒ s"Unable to get module symbol for $instance due to: $e")

    def resolveComment(symbol: Symbol) /*: Xor[String, Comment] */ = {
      val path = symbolToPath(symbol)
      Xor.fromOption(
        sourceExtracted.comments.get(path).map(_.comment),
        s"""Unable to retrieve doc comment for ${path.mkString(".")}"""
      )
    }

    def resolveMethodBody(symbol: Symbol): Xor[String, String] = {
      val path = symbolToPath(symbol)
      Xor.fromOption(
        sourceExtracted.methodBodies.get(path).map(_.code),
        s"""Unable to retrieve code for method ${path.mkString(".")}"""
      )
    }

    def symbolToPath(symbol: Symbol): List[String] = {
      def process(symbol: Symbol): List[String] = {
        lazy val owner = symbol.owner
        unapplyRawName(symbol.name) match {
          case `ROOT`                      ⇒ Nil
          case `EMPTY_PACKAGE_NAME_STRING` ⇒ Nil
          case `ROOTPKG_STRING`            ⇒ Nil
          case value if symbol != owner    ⇒ value :: process(owner)
          case _                           ⇒ Nil
        }
      }
      process(symbol).reverse
    }

    private[compiler] def unapplyRawName(name: Name): String = name match {
      case TermName(value) ⇒ value
      case TypeName(value) ⇒ value
    }

    private lazy val EMPTY_PACKAGE_NAME_STRING = unapplyRawName(termNames.EMPTY_PACKAGE_NAME)
    private lazy val ROOTPKG_STRING = unapplyRawName(termNames.ROOTPKG)
    private lazy val ROOT = "<root>" // can't find an accessible constant for this

  }

}
