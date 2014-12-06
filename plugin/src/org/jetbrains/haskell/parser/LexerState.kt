package org.jetbrains.haskell.parser

import com.intellij.psi.tree.IElementType
import org.jetbrains.grammar.HaskellLexerTokens
import java.util.Arrays
import java.util.HashSet
import java.util.ArrayList
import org.jetbrains.haskell.parser.lexer.HaskellLexer
import com.intellij.psi.TokenType
import org.jetbrains.haskell.parser.token.NEW_LINE
import org.jetbrains.haskell.parser.token.END_OF_LINE_COMMENT
import org.jetbrains.haskell.parser.token.BLOCK_COMMENT
import java.io.PrintStream
import com.intellij.lang.PsiBuilder
import com.intellij.lang.WhitespaceSkippedCallback
import org.jetbrains.grammar.dumb.NonTerminalTree
import org.jetbrains.grammar.dumb.TerminalTree
import org.jetbrains.haskell.parser.token.PRAGMA

val INDENT_TOKENS = HashSet<IElementType>(Arrays.asList(
        HaskellLexerTokens.DO,
        HaskellLexerTokens.OF,
        HaskellLexerTokens.LET,
        HaskellLexerTokens.WHERE))

class IntStack(val indent: Int,
               val parent: IntStack?)

public fun getCachedTokens(lexer: HaskellLexer, stream: PrintStream): CachedTokens {
    val tokens = ArrayList<IElementType>()
    val starts = ArrayList<Int>()
    val indents = ArrayList<Int>()
    val lineStarts = ArrayList<Boolean>()

    var lineStartOffset = 0
    var isLineStart = true

    stream.println("-------------------")
    while (lexer.getTokenType() != null) {
        val tokenType = lexer.getTokenType()
        if (tokenType != TokenType.WHITE_SPACE &&
                tokenType != END_OF_LINE_COMMENT &&
                tokenType != BLOCK_COMMENT &&
                tokenType != PRAGMA) {
            if (tokenType == NEW_LINE) {
                lineStartOffset = lexer.getTokenEnd()
                isLineStart = true
                stream.println()
            } else {
                tokens.add(tokenType)
                starts.add(lexer.getTokenStart())
                indents.add(lexer.getTokenStart() - lineStartOffset)
                lineStarts.add(isLineStart)
                isLineStart = false
                stream.print("${tokenType} ")
            }
        }
        lexer.advance();
    }
    stream.println("-------------------")
    return CachedTokens(tokens, starts, indents, lineStarts)
}

public fun getCachedTokens(builder: PsiBuilder): CachedTokens {
    val tokens = ArrayList<IElementType>()
    val starts = ArrayList<Int>()
    val indents = ArrayList<Int>()
    val lineStarts = ArrayList<Boolean>()

    var lineStartOffset = 0
    var isLineStart = true

    builder.setWhitespaceSkippedCallback(object : WhitespaceSkippedCallback {
        override fun onSkip(type: IElementType?, start: Int, end: Int) {
            if (type == NEW_LINE) {
                lineStartOffset = end
                isLineStart = true
            }
        }

    })

    while (builder.getTokenType() != null) {
        tokens.add(builder.getTokenType())
        starts.add(builder.getCurrentOffset())
        indents.add(builder.getCurrentOffset() - lineStartOffset)
        lineStarts.add(isLineStart)
        isLineStart = false
        builder.advanceLexer()
    }

    return CachedTokens(tokens, starts, indents, lineStarts)
}

public fun newParserState(tokens: CachedTokens): LexerState {
    return LexerState(tokens, 0, 0, null, null)
}

public class CachedTokens(val tokens: List<IElementType>,
                          val starts: List<Int>,
                          val indents: ArrayList<Int>,
                          val lineStart: ArrayList<Boolean>) {
}

public class LexerState(val tokens: CachedTokens,
                         val position: Int,
                         val lexemNumber: Int,
                         val currentToken: HaskellTokenType?,
                         val indentStack: IntStack?) {

    fun match(token: HaskellTokenType): Boolean {
        if (currentToken != null) {
            return currentToken == token
        }
        if (position < tokens.tokens.size && tokens.tokens[position] == token) {
            return true
        }
        return false
    }

    fun next(): LexerState {
        if (currentToken != null) {
            if (currentToken == HaskellLexerTokens.VCCURLY && indentStack != null) {
                if (position == tokens.tokens.size) {
                    return LexerState(
                            tokens,
                            position,
                            lexemNumber + 1,
                            HaskellLexerTokens.VCCURLY,
                            indentStack.parent)
                } else {
                    val indent = tokens.indents[position]
                    if (indentStack.indent == indent) {
                        return LexerState(tokens, position, lexemNumber + 1, HaskellLexerTokens.SEMI, indentStack)
                    } else if (indentStack.indent < indent) {
                        return checkCurly(position)
                    } else {
                        return LexerState(tokens, position, lexemNumber + 1, HaskellLexerTokens.VCCURLY, indentStack.parent)
                    }
                }
            }
            return LexerState(tokens, position, lexemNumber + 1, null, indentStack)
        }
        if (position == tokens.tokens.size) {
            return LexerState(
                    tokens,
                    position,
                    lexemNumber + 1,
                    null,
                    indentStack)
        }
        if (tokens.tokens[position] == HaskellLexerTokens.OCURLY) {
            return LexerState(tokens,
                    position + 1,
                    lexemNumber + 1,
                    null,
                    IntStack(-1, indentStack))
        }
        if (INDENT_TOKENS.contains(tokens.tokens[position])) {
            val nextPosition = position + 1
            if (tokens.tokens[nextPosition] == HaskellLexerTokens.OCURLY) {
                return LexerState(
                        tokens,
                        nextPosition,
                        lexemNumber + 1,
                        null,
                        indentStack)
            }
            val indent = tokens.indents[nextPosition]
            return LexerState(tokens,
                    nextPosition,
                    lexemNumber + 1,
                    HaskellLexerTokens.VOCURLY,
                    IntStack(indent, indentStack))
        }
        val nextPosition = position + 1;
        if (nextPosition == tokens.tokens.size) {
            if (indentStack != null) {
                return LexerState(tokens,
                        nextPosition,
                        lexemNumber + 1,
                        HaskellLexerTokens.VCCURLY,
                        indentStack.parent)
            } else {
                return LexerState(tokens,
                        nextPosition,
                        lexemNumber + 1,
                        null,
                        null)
            }
        }
        if (tokens.lineStart[nextPosition]) {
            val indent = tokens.indents[nextPosition]
            if (indentStack != null) {
                if (indentStack.indent == indent) {
                    return LexerState(tokens, nextPosition, lexemNumber + 1, HaskellLexerTokens.SEMI, indentStack)
                } else if (indentStack.indent < indent) {
                    return checkCurly(nextPosition)
                } else {
                    return LexerState(tokens, nextPosition, lexemNumber + 1, HaskellLexerTokens.VCCURLY, indentStack.parent)
                }
            }
        }
        return checkCurly(nextPosition)

    }

    private fun checkCurly(nextPosition: Int): LexerState {
        if (tokens.tokens[nextPosition] == HaskellLexerTokens.CCURLY) {
            if (indentStack!!.indent > -1) {
                return LexerState(tokens, nextPosition - 1, lexemNumber + 1, HaskellLexerTokens.VCCURLY, indentStack.parent)
            }
            return LexerState(tokens, nextPosition, lexemNumber + 1, null, indentStack.parent)
        }
        return LexerState(tokens, nextPosition, lexemNumber + 1, null, indentStack)
    }

    fun skip(tree: NonTerminalTree): LexerState {
        var current: LexerState = this

        for (child in tree.children) {
            when (child) {
                is TerminalTree -> {
                    if (current.getToken() == child.haskellToken) {
                        current = current.next()
                    } else {
                        current = current.dropIndent().next()
                    }
                }
                is NonTerminalTree -> {
                    current = current.skip(child)
                }
            }
        }

        return current
    }

    fun dropIndent() = LexerState(
            tokens,
            position,
            lexemNumber + 1,
            HaskellLexerTokens.VCCURLY,
            indentStack!!.parent)

    fun getToken(): IElementType? {
        if (currentToken != null) {
            return currentToken
        }
        if (position < tokens.tokens.size) {
            return tokens.tokens[position];
        }
        return null;
    }

    fun eof(): Boolean {
        return currentToken == null && position == tokens.tokens.size;
    }


}