package com.twitter.config.parser

import com.twitter.config.{Group, SettingOverride}
import com.twitter.config.adt._
import fastparse.all._

trait ParsedLine

case class EmptyLine() extends ParsedLine

case class ParsedComment(comment: String) extends ParsedLine

case class ParsedSettingValue[T](setting: SettingValue[T]) extends ParsedLine

case class ParsedGroup(value: Group) extends ParsedLine

case class ParsedOrphanedLine(value: String) extends ParsedLine

case class NamedFunction[T, V](f: T => V, name: String) extends (T => V){
  def apply(t: T) = f(t)
  override def toString() = name
}

trait ConfigParser {

  val eol = sys.props.get("line.separator").getOrElse(throw new RuntimeException("Could not get line separator"))
  val eoz = "\\z"

  val Whitespace = NamedFunction(" \r\n".contains(_: Char), "Whitespace")
  val Digits = NamedFunction('0' to '9' contains (_: Char), "Digits")
  val NonLineEnding = NamedFunction(!"\r\n".contains(_: Char), "StringChars")
  val nonDelimited = NamedFunction(!",\r\n".contains(_: Char), "StringChars")
  val NonWhitespace = NamedFunction(!" \r\n".contains(_: Char), "StringChars")
  val nonDelimitedSetting = NamedFunction(!" <>,=\r\n".contains(_: Char), "SettingStringChars")
  val AnyStringChars = NamedFunction(!"\"\\".contains(_: Char), "AnyStringChars")

  val groupParser: P[ParsedGroup] = P(("[" ~ CharsWhile(_ != ']').! ~ "]").map(str => ParsedGroup(Group(str))))

  val overrideParser: P[SettingOverride] = P("<" ~/ CharsWhile(ch => ch != '>' && ch != '<').rep(1).! ~/ ">").map(SettingOverride)

  val trueParser = P(("true" | "yes").!).map(_ => true)
  val falseParser = P(("false" | "no").!).map(_ => false)

  val booleanParser: P[BooleanConfigValue] = P(trueParser | falseParser).map(BooleanConfigValue)

  val signParser: P[String] = P("-" | "+").!
  val digitParser = P(signParser.? ~ CharIn('0' to '9').rep(1)).!
  val rawLongParser: P[Long] = digitParser.!.map(_.toLong)

  /**
    * Numeric parser for Long should check Long overflow.
    */
  val numParser: P[LongValue] = P(rawLongParser ~ End).map(LongValue)

  val space = P(CharIn(Seq('\r', '\n', ' ')).?)

  val numberSeq = P(rawLongParser.!.map(_.toLong).rep(min = 2, sep = ","))

  val numberList: P[NumericListValue] = P(numberSeq ~ End) map NumericListValue

  val stringRaw: P[String] = P(CharsWhile(!",;\r\n".contains(_))).!

  val hexDigit = P(CharIn('0' to '9', 'a' to 'f', 'A' to 'F'))
  val unicodeEscape = P("u" ~ hexDigit ~ hexDigit ~ hexDigit ~ hexDigit)
  val escape = P("\\" ~ (CharIn("\"/\\bfnrt") | unicodeEscape))

  val strChars = P(CharsWhile(AnyStringChars) )

  val quotedString: P[StringValue] = P( space ~ "\"" ~/ (strChars | escape).rep.! ~ "\"").map(StringValue)

  val strParser: P[StringValue] = stringRaw map StringValue

  val strSeq: P[Seq[String]] = P(stringRaw).rep(min = 2, sep = ",")

  val stringList: P[StringListValue] = P(strSeq ~ End) map StringListValue

  val valueParser: P[ConfigValue[_]] = P(numberList | quotedString | stringList | booleanParser | numParser | strParser)

  val settingKeyParser = P(CharsWhile(nonDelimitedSetting).rep(1).! ~ overrideParser.?)

  val settingParser: P[ParsedSettingValue[_]] = P(settingKeyParser ~ space ~ "=" ~ space ~ valueParser).map {
    case (key, groupOverride, value) => {
      ParsedSettingValue(SettingValue(key, value, groupOverride))
    }
  }

  val commentParser: P[ParsedComment] = P(Start ~ ";" ~/ CharsWhile(NonLineEnding).!).map(ParsedComment)

  val orphanedLine: P[ParsedOrphanedLine] = P(CharsWhile(!";\r\n".contains(_)).rep.!).map(ParsedOrphanedLine)

  val lineParser: P[ParsedLine] = P(groupParser | settingParser | commentParser | orphanedLine)
}
