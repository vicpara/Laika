/*
 * Copyright 2014-2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package laika.parse.css

import laika.io.Input
import laika.parse.core.markup.InlineParsers
import laika.parse.core.text.MarkupParser
import laika.parse.core.text.TextParsers._
import laika.parse.core.{Parser, ParserContext}
import laika.parse.css.Styles._
import laika.tree.Paths.Path
import laika.util.~

/**
 * Parsers for the subset of CSS supported by Laika.
 * 
 * Not supported are attribute selectors, pseudo classes, namespaces and media queries.
 * 
 * @author Jens Halm
 */
trait CSSParsers {

  
  /** Represents a combinator between two predicates.
   */
  sealed abstract class Combinator
  
  /** A combinator for a descendant on any nesting
   *  level.
   */
  case object Descendant extends Combinator
  
  /** A combinator for an immediate child.
   */
  case object Child extends Combinator

  /** Represents a single style within a style
   *  declaration.
   */
  case class Style (name: String, value: String)
  
  /** Parses horizontal whitespace or newline characters.
   */
  val wsOrNl: Parser[String] = anyOf(' ', '\t', '\n', '\r')


  /** Parses the name of a style. The name must start
   *  with a letter, while subsequent characters can be 
   *  letters, digits or one of the symbols `'-'` or `'_'`.
   */
  val styleRefName: Parser[String] = {
    val alpha = anyWhile(c => Character.isLetter(c)) min 1
    val alphanum = anyWhile(c => Character.isDigit(c) || Character.isLetter(c)) min 1
    val symbol = anyOf('-', '_') max 1
    
    alpha ~ ((symbol ~ alphanum)*) ^^ { 
      case start ~ rest => start + (rest map { case a~b => a+b }).mkString
    }
  }
  
  /** Parses a combinator between two predicates.
   */
  val combinator: Parser[Combinator] =
    ((ws ~ '>' ~ ws) ^^^ Child) | (ws min 1) ^^^ Descendant

  /** Parses a single type selector.
   */
  val typeSelector: Parser[List[Predicate]] =
    (styleRefName ^^ { name => List(ElementType(name)) }) | ('*' ^^^ Nil) 
    
  /** Parses a single predicate.
   */
  val predicate: Parser[Predicate] = {
    
    val id: Parser[Predicate] = ('#' ~> styleRefName) ^^ Id
    val styleName: Parser[Predicate] = ('.' ~> styleRefName) ^^ StyleName
    
    id | styleName
  }

  /** Parses the sub-part of a selector without any combinators, e.g. `Paragraph#title`.
    */
  val simpleSelectorSequence: Parser[Selector] =
    (((typeSelector ~ (predicate*)) ^^ { case preds1 ~ preds2 => preds1 ::: preds2 }) | (predicate+)) ^^ {
      preds => Selector(preds.toSet)
    }

  /** Parses a single selector.
    */
  val selector: Parser[Selector] =
    simpleSelectorSequence ~ ((combinator ~ simpleSelectorSequence)*) ^^ {
      case sel ~ sels => (sel /: sels) {
        case (parent, Child ~ sel)      => sel.copy(parent = Some(ParentSelector(parent, immediate = true)))
        case (parent, Descendant ~ sel) => sel.copy(parent = Some(ParentSelector(parent, immediate = false)))
      }
    }

  /** Parses a sequence of selectors, separated by a comma.
    */
  val selectorGroup: Parser[Seq[Selector]] =
    selector ~ ((ws ~ ',' ~ ws ~> selector)*) ^^ { case sel ~ sels => sel :: sels }

  /** Parses the value of a single style, ignoring
    *  any comments..
    */
  val styleValue: Parser[String] =
    InlineParsers.text(delimitedBy(';'), Map('/' -> (('*' ~ delimitedBy("*/") ~ wsOrNl) ^^^ "")))

  /** Parses a single style within a declaration.
    */
  val style: Parser[Style] = ((styleRefName <~ ws ~ ':' ~ ws) ~ (styleValue <~ wsOrNl)) ^^ {
    case name ~ value => Style(name, value)
  }

  /** Parses a single CSS comment.
    */
  val comment: Parser[Unit] = ("/*" ~ delimitedBy("*/") ~ wsOrNl) ^^^ (())
  
  /** Parses a sequence of style declarations, ignoring
   *  any comments.
   */
  val styleDeclarations: Parser[Seq[StyleDeclaration]] =
    ((selectorGroup <~ wsOrNl ~ '{' ~ wsOrNl) ~ ((comment | style)*) <~ (wsOrNl ~ '}')) ^^ {
      case selectors ~ stylesAndComments => 
        val styles = stylesAndComments collect { case st: Style => (st.name, st.value) } toMap;
        selectors map (StyleDeclaration(_, styles))
    }
  
  /** Parses an entire set of style declarations.
   *  This is the top level parser of this trait.
   */
  lazy val styleDeclarationSet: MarkupParser[Set[StyleDeclaration]] = {

    val baseParser = (wsOrNl ~ (comment*) ~> ((styleDeclarations <~ wsOrNl ~ (comment*))*)) ^^ {
      _.flatten.zipWithIndex.map({
        case (decl,pos) => decl.increaseOrderBy(pos)
      }).toSet
    }

    /* TODO - needs better error handling, the `MarkupParser` was only meant to be used
       with text markup that is never supposed to fail as there is always a plain-text fallback-parser.
       But CSS can contain actual errors that prevent successful parsing.
     */

    new MarkupParser(baseParser)
  }

  /** Fully parses the input from the specified reader and returns the resulting
   *  style declaration set.
   *  
   *  @param ctx the character input to process
   *  @param path the path the input has been obtained from
   *  @return the resulting set of style declarations
   */
  def parseStyleSheet (ctx: ParserContext, path: Path): StyleDeclarationSet = {
    val set = styleDeclarationSet.parseMarkup(ctx)
    new StyleDeclarationSet(Set(path), set)
  }
    
}

/** Companion for accessing the default implementation.
 */
object CSSParsers {
  
  /** The default parser function for Laika's CSS support.
   */
  lazy val default: Input => StyleDeclarationSet = {
    val parser = new CSSParsers {}
    input => parser.parseStyleSheet(input.asParserInput, input.path)
  }
  
}
