/*
 * Copyright 2013-2016 the original author or authors.
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

package laika.directive

import com.typesafe.config.Config
import laika.directive.Directives._
import laika.parse.core.markup._
import laika.parse.core.text.TextParsers._
import laika.parse.core.{Parser, Failure => PFailure, Success => PSuccess}
import laika.rewrite.DocumentCursor
import laika.template.ConfigParser
import laika.tree.Elements._
import laika.tree.Paths.Path
import laika.tree.Templates._
import laika.util.~

/** Parsers for all types of custom directives that can be used
 *  in templates or as inline or block elements in markup documents.
 *  
 *  @author Jens Halm
 */
class DirectiveParsers (escapedText: EscapedTextParsers) {
  
  
  /** Groups the result of the parser and the source string
   *  that it successfully parsed into a tupled result. 
   */
  def withSource[T] (p: Parser[T]): Parser[(T, String)] = Parser { in =>
    p.parse(in) match {
      case PSuccess(result, next) => PSuccess((result, in.capture(next.offset - in.offset)), next)
      case f: PFailure            => f
    }
  }


  /** Parses a reference enclosed between `{{` and `}}`.
    */
  def reference[T] (f: String => T): Parser[T] =
  '{' ~ ws ~> refName <~ ws ~ "}}" ^^ f


  /** Represents one part of a directive (an attribute or a body element).
   */
  case class Part (key: Key, content: String)

  /** Represents the parsed but unprocessed content of a directive.
   */
  case class ParsedDirective (name: String, parts: List[Part])
  
  type PartMap = Map[Key, String]
  
  
  /** Parses horizontal whitespace or newline characters.
   */
  lazy val wsOrNl: Parser[String] = anyOf(' ','\t', '\n')

  /** Parses a name declaration that start with a letter and 
   *  continues with letters, numbers or the symbols '-' or '_'.
   */
  lazy val nameDecl: Parser[String] = (anyIn('a' to 'z', 'A' to 'Z') take 1) ~ 
    anyIn('a' to 'z', 'A' to 'Z', '0' to '9', '-', '_') ^^ { case first ~ rest => first + rest }
  
  /** Parses a full directive declaration, containing all its attributes,
   *  but not the body elements.
   */
  lazy val declaration: Parser[(String, List[Part])] = {
    
    lazy val attrName: Parser[String] = nameDecl <~ wsOrNl ~ '=' ~ wsOrNl
  
    lazy val attrValue: Parser[String] =
      '"' ~> escapedText.escapedUntil('"') | (anyBut(' ','\t','\n','.',':') min 1)
  
    lazy val defaultAttribute: Parser[Part] = not(attrName) ~> attrValue ^^ { Part(Attribute(Default), _) }
  
    lazy val attribute: Parser[Part] = attrName ~ attrValue ^^ { case name ~ value => Part(Attribute(name), value) }
 
    (":" ~> nameDecl <~ wsOrNl) ~ opt(defaultAttribute <~ wsOrNl) ~ ((wsOrNl ~> attribute)*) <~ ws ^^ 
    { case name ~ defAttr ~ attrs => (name, defAttr.toList ::: attrs) }
 }
      

  private lazy val bodyName: Parser[String] = '~' ~> nameDecl <~ ws ~ ':'
  
  private lazy val noBody: Parser[List[Part]] = '.' ^^^ List[Part]()
  
  /** Parses one directive instance containing its name declaration,
   *  all attributes and all body elements.
   *  
   *  @param bodyContent the parser for the body content which is different for a block directive than for a span or template directive
   *  @param includeStartChar indicates whether the starting '@' has to be parsed by this parser
   */
  def directiveParser (bodyContent: Parser[String], includeStartChar: Boolean): Parser[ParsedDirective] = {
    
    val defaultBody: Parser[Part] = not(wsOrNl ~> bodyName) ~> bodyContent ^^ { Part(Body(Default),_) }
    
    val body: Parser[Part] = wsOrNl ~> bodyName ~ bodyContent ^^ { case name ~ content => Part(Body(name), content) }
    
    val bodies = ':' ~> (defaultBody | body) ~ (body*) ^^ { case first ~ rest => first :: rest }
    
    val decl = if (includeStartChar) "@" ~> declaration else declaration
    decl ~ (noBody | bodies) ^^ { case (name, attrs) ~ bodies => ParsedDirective(name, attrs ::: bodies) }
  }
  
  abstract class DirectiveContextBase (parts: PartMap, docCursor: Option[DocumentCursor] = None) {
    
    def part (key: Key): Option[String] = parts.get(key)
      
    val cursor: Option[DocumentCursor] = docCursor
    
  }
  
  def applyDirective [E <: Element] (builder: BuilderContext[E]) (
      parseResult: ParsedDirective, 
      directives: String => Option[builder.Directive], 
      createContext: (PartMap, Option[DocumentCursor]) => builder.DirectiveContext,
      createPlaceholder: (DocumentCursor => E) => E, 
      createInvalidElement: String => E,
      directiveTypeDesc: String
    ): E = {
    
    val directive = directives(parseResult.name).map(Directives.Success(_))
        .getOrElse(Directives.Failure(s"No $directiveTypeDesc directive registered with name: ${parseResult.name}"))
    
    val partMap = {
      val dups = parseResult.parts groupBy (_.key) filterNot (_._2.tail.isEmpty) keySet;
      if (dups.isEmpty) Directives.Success(parseResult.parts map (p => (p.key, p.content)) toMap)
      else Directives.Failure(dups.map("Duplicate "+_.desc).toList)
    }
    
    def processResult (result: Directives.Result[E]) = result match {
      case Directives.Success(result)   => result
      case Directives.Failure(messages) => createInvalidElement("One or more errors processing directive '"
          + parseResult.name + "': " + messages.mkString(", "))
    }
    
    processResult((directive ~ partMap) flatMap { case directive ~ partMap =>
      def directiveWithContext (cursor: Option[DocumentCursor]) = directive(createContext(partMap, cursor))
      if (directive.requiresContext) Directives.Success(createPlaceholder(c => processResult(directiveWithContext(Some(c)))))
      else directiveWithContext(None)
    }) 
  }
  
  val nestedBraces = delimitedBy('}') ^^ (str => Text(s"{$str}"))
  
}


/** Provides the parsers for directives in markup documents.
  */
class MarkupDirectiveParsers(recParsers: RecursiveParsers,
                             blockDirectives: Map[String, Blocks.Directive],
                             spanDirectives: Map[String, Spans.Directive]) {

  val directiveParsers = new DirectiveParsers(recParsers)

  import directiveParsers._
  import BlockParsers._
  import recParsers._


  case class DirectiveSpan (f: DocumentCursor => Span, options: Options = NoOpt) extends SpanResolver {
    def resolve (cursor: DocumentCursor) = f(cursor)
  }

  case class DirectiveBlock (f: DocumentCursor => Block, options: Options = NoOpt) extends BlockResolver {
    def resolve (cursor: DocumentCursor) = f(cursor)
  }

  val spanParsers = Map(
    '{' -> reference(MarkupContextReference(_)),
    '@' -> spanDirective
  )


  def config (path: Path): Parser[Either[InvalidBlock,Config]] = ConfigParser.forPath(path, {
    (ex: Exception, str: String) => InvalidBlock(SystemMessage(laika.tree.Elements.Error,
      "Error parsing config header: "+ex.getMessage), LiteralBlock(s"{%$str%}"))
  })

  lazy val spanDirective: Parser[Span] = {
    val contextRefOrNestedBraces = Map('{' -> (reference(MarkupContextReference(_)) | nestedBraces))
    val bodyContent = wsOrNl ~ '{' ~> (withSource(delimitedRecursiveSpans(delimitedBy('}'), contextRefOrNestedBraces)) ^^ (_._2.dropRight(1)))
    withRecursiveSpanParser(withSource(directiveParser(bodyContent, includeStartChar = false))) ^^ {
      case (recParser, (result, source)) => // TODO - optimization - parsed spans might be cached for DirectiveContext (applies for the template parser, too)

        def createContext (parts: PartMap, docCursor: Option[DocumentCursor]): Spans.DirectiveContext = {
          new DirectiveContextBase(parts, docCursor) with Spans.DirectiveContext {
            val parser = new Spans.Parser {
              def apply (source: String) = recParser(source)
            }
          }
        }
        def invalid (msg: String) = InvalidSpan(SystemMessage(laika.tree.Elements.Error, msg), Literal("@"+source))

        applyDirective(Spans)(result, spanDirectives.get, createContext, DirectiveSpan(_), invalid, "span")
    }
  }

  lazy val blockDirective: Parser[Block] = {
    val bodyContent = indentedBlock() ^^? { block =>
      val trimmed = block.trim
      Either.cond(trimmed.nonEmpty, trimmed, "empty body")
    }
    withRecursiveSpanParser(withRecursiveBlockParser(withSource(directiveParser(bodyContent, includeStartChar = true)))) ^^ {
      case (recSpanParser, (recBlockParser, (result, source))) =>

        def createContext (parts: PartMap, docCursor: Option[DocumentCursor]): Blocks.DirectiveContext = {
          new DirectiveContextBase(parts, docCursor) with Blocks.DirectiveContext {
            val parser = new Blocks.Parser {
              def apply (source: String): Seq[Block] = recBlockParser(source)
              def parseInline (source: String): Seq[Span] = recSpanParser(source)
            }
          }
        }
        def invalid (msg: String) = InvalidBlock(SystemMessage(laika.tree.Elements.Error, msg), LiteralBlock(source))

        applyDirective(Blocks)(result, blockDirectives.get, createContext, DirectiveBlock(_), invalid, "block")
    }
  }
    

}
