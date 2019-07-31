package de.pault.idea.javaScriptExtendedFolding

import com.intellij.lang.ASTNode
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.lang.javascript.JSElementTypes.*
import com.intellij.lang.javascript.JSTokenTypes.*
import com.intellij.lang.javascript.folding.JavaScriptFoldingBuilder
import com.intellij.lang.javascript.types.JSFileElementType
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.FoldingGroup
import com.intellij.openapi.util.TextRange
import com.intellij.psi.tree.TokenSet

class Folding : JavaScriptFoldingBuilder() {

    override fun appendDescriptors(node: ASTNode, document: Document, descriptors: MutableList<in FoldingDescriptor>): ASTNode {
        startFolding(node, descriptors)

        return node
    }

    private fun startFolding(node: ASTNode, descriptors: MutableList<in FoldingDescriptor>): ASTNode {
        when {
            FUNCTION_EXPRESSIONS.contains(node.elementType) -> this.foldFunctionExpression(node, descriptors)
            node.elementType.toString() == JS_PROPERTY -> this.foldProperty(node, descriptors)
            node.elementType == BINARY_EXPRESSION -> this.foldStringInterpolation(node, descriptors)
            node.elementType.toString() == JS_CALL_EXPRESSION -> this.foldLodashBindThis(node, descriptors)
        }


        if (node.elementType is JSFileElementType) {
            // expand chameleon
            node.psi.firstChild
        }

        var child: ASTNode? = node.firstChildNode
        while (child != null) {
            if (child.elementType == WHITE_SPACE) {
                child = child.treeNext
                continue
            }
            child = startFolding(child, descriptors).treeNext
        }

        return node
    }

    private fun foldLodashBindThis(node: ASTNode, descriptors: MutableList<in FoldingDescriptor>) {
        val caller = node.getChildren(TokenSet.create(REFERENCE_EXPRESSION)).first() ?: return
        caller.getChildren(TokenSet.create(REFERENCE_EXPRESSION)).firstOrNull { it.text == "_" } ?: return
        caller.getChildren(TokenSet.create(IDENTIFIER)).firstOrNull { it.text == "bind" } ?: return
        val argumentList = node.getChildren(TokenSet.create(ARGUMENT_LIST)).firstOrNull() ?: return

        argumentList.findChildByType(THIS_EXPRESSION) ?: return
        val function = argumentList.findChildByType(FUNCTION_EXPRESSIONS) ?: return
        val params = function.findChildByType(PARAMETER_LISTS) ?: return

        val lPar = params.findChildByType(LPAR) ?: return
        val rPar = params.findChildByType(RPAR) ?: return

        val block = function.findChildByType(BLOCK_STATEMENT) ?: return

        val lBrace = block.findChildByType(LBRACE) ?: return
        val rBrace = block.findChildByType(RBRACE) ?: return

        val group = FoldingGroup.newGroup("lodash bind to arrow")

        descriptors.add(object : FoldingDescriptor(node,
                TextRange(caller.startOffset, lPar.startOffset), group
        ) {
            override fun getPlaceholderText(): String? {
                return ""
            }
        })

        descriptors.add(object : FoldingDescriptor(node, lPar.textRange, group) {
            override fun getPlaceholderText(): String? {
                return "("
            }
        })

        descriptors.add(object : FoldingDescriptor(node,
                TextRange(rPar.startOffset, lBrace.startOffset), group
        ) {
            override fun getPlaceholderText(): String? {
                return ") => "
            }
        })

        descriptors.add(object : FoldingDescriptor(node,
                TextRange(rBrace.textRange.endOffset, node.textRange.endOffset), group
        ) {
            override fun getPlaceholderText(): String? {
                return ""
            }
        })
    }

    private fun foldStringInterpolation(node: ASTNode, descriptors: MutableList<in FoldingDescriptor>) {
        val binaryChildren = node.getChildren(TokenSet.create(BINARY_EXPRESSION))
        val plusChildren = node.getChildren(TokenSet.create(PLUS))
        val literalChildren = node.getChildren(TokenSet.ANY).filter { it.elementType.toString() == JS_LITERAL_EXPRESSION }

        if (binaryChildren.size == 1 && plusChildren.size == 1 && literalChildren.size == 1 &&
                (literalChildren[0].text.startsWith("'") || literalChildren[0].text.startsWith("\""))) {
            val plus = plusChildren[0]
            val literal1 = literalChildren[0]
            val rtl = plus.startOffset < literal1.startOffset
            val literal2 = if (rtl) {
                binaryChildren[0].firstChildNode
            } else {
                binaryChildren[0].lastChildNode
            }
            val middle = if (rtl) {
                binaryChildren[0].lastChildNode
            } else {
                binaryChildren[0].firstChildNode
            }
            val quoteSignLiteral1 = if (rtl) {
                literal1.text.first()
            } else {
                literal1.text.last()
            }

//            val quoteSignLiteral2 = if (rtl) {
//                literal2.text.last()
//            } else {
//                literal2.text.first()
//            }

            if (quoteSignLiteral1 == '\'' || quoteSignLiteral1 == '"') {
                val group = FoldingGroup.newGroup("es6 template literal")

                val start = if (rtl) {
                    literal2.textRange.endOffset - 1
                } else {
                    literal1.textRange.endOffset - 1
                }

                val end = if (rtl) {
                    literal1.startOffset + 1
                } else {
                    literal2.startOffset + 1
                }

                descriptors.add(object : FoldingDescriptor(node,
                        TextRange(start, middle.startOffset), group) {
                    override fun getPlaceholderText(): String? {
                        return "\${"
                    }
                })

                descriptors.add(object : FoldingDescriptor(node,
                        TextRange(middle.textRange.endOffset, end), group) {
                    override fun getPlaceholderText(): String? {
                        return "}"
                    }
                })

            }

        }
    }

    private fun foldProperty(node: ASTNode, descriptors: MutableList<in FoldingDescriptor>) {
        // { property: property, } -> { property, }
        val identifier = node.findChildByType(IDENTIFIER)
        val ref = node.findChildByType(REFERENCE_EXPRESSION)
        val refIdent = if (ref?.firstChildNode?.elementType === IDENTIFIER) {
            ref?.firstChildNode
        } else {
            null
        }
        if (identifier != null && refIdent !== null && identifier.text == refIdent.text) {
            descriptors.add(object : FoldingDescriptor(node,
                    node.textRange) {
                override fun getPlaceholderText(): String? {
                    return identifier.text
                }
            })
        }
    }

    private fun foldFunctionExpression(node: ASTNode, descriptors: MutableList<in FoldingDescriptor>) {
        val parent = node.treeParent

        when {
            parent.elementType == REFERENCE_EXPRESSION -> foldBindThis(node, descriptors)
            parent.elementType.toString() == JS_PROPERTY -> foldPropertyFunction(node, descriptors)
            node.findChildByType(EQGT) == null -> foldSlimArrowFunction(node, descriptors)
        }
    }

    private fun foldBindThis(node: ASTNode, descriptors: MutableList<in FoldingDescriptor>) {
        val parent = node.treeParent
        val treeNext = parent.treeNext
        val lastChildNode = parent.lastChildNode

        if (lastChildNode.elementType == IDENTIFIER && lastChildNode.text == "bind") {
            if (treeNext.getChildren(TokenSet.ANY).firstOrNull { it.elementType == THIS_EXPRESSION } != null) {
                val group = FoldingGroup.newGroup("arrow function")
                val params = node.findChildByType(PARAMETER_LISTS)
                val lPar = params?.findChildByType(LPAR)
                val rPar = params?.findChildByType(RPAR)
                val block = node.findChildByType(BLOCK_STATEMENT)
                val functionKeyword = node.findChildByType(FUNCTION_KEYWORD)

                val argumentsAreOnlyWhiteSpaces = params?.getChildren(TokenSet.ANY)
                        ?.all {
                            it.elementType == WHITE_SPACE ||
                                    it.elementType == LPAR ||
                                    it.elementType == RPAR
                        }
                val argumentsContainComma = params?.getChildren(TokenSet.ANY)
                        ?.any {
                            it.elementType == COMMA
                        }
                val doParenthesis = argumentsAreOnlyWhiteSpaces == true || argumentsContainComma == true

                if (lPar != null && rPar != null && block != null && functionKeyword != null) {
                    descriptors.add(object : FoldingDescriptor(parent.treeParent,
                            TextRange(functionKeyword.startOffset, lPar.textRange.endOffset), group) {
                        override fun getPlaceholderText(): String? {
                            return if (doParenthesis) {
                                "("
                            } else {
                                ""
                            }
                        }
                    })

                    descriptors.add(object : FoldingDescriptor(parent.treeParent,
                            TextRange(rPar.startOffset, block.startOffset), group) {
                        override fun getPlaceholderText(): String? {
                            return if (doParenthesis) {
                                ") => "
                            } else {
                                " => "
                            }
                        }
                    })
                    descriptors.add(object : FoldingDescriptor(parent.treeParent.treeParent,
                            TextRange(block.textRange.endOffset, treeNext.textRange.endOffset), group) {
                        override fun getPlaceholderText(): String? {
                            return ""
                        }
                    })
                }
            }
        }
    }

    private fun foldPropertyFunction(node: ASTNode, descriptors: MutableList<in FoldingDescriptor>) {
        val parent = node.treeParent

        val group = FoldingGroup.newGroup("es6 property function")
        val params = node.findChildByType(PARAMETER_LISTS)
        val lPar = params?.findChildByType(LPAR)
        val rPar = params?.findChildByType(RPAR)
        val propertyName = parent.findChildByType(IDENTIFIER)

        if (lPar != null && rPar != null && propertyName != null) {
            val attributes = node.getChildren(ATTRIBUTE_LISTS)
            if (attributes.isNotEmpty() && attributes[0].text.isNotEmpty()) {
                descriptors.add(object : FoldingDescriptor(parent,
                        propertyName.textRange, group) {
                    override fun getPlaceholderText(): String? {
                        return attributes.map { it.text }.joinToString { "$it " } + propertyName.text
                    }
                })
            }

            descriptors.add(object : FoldingDescriptor(parent,
                    TextRange(propertyName.textRange.endOffset, lPar.textRange.endOffset), group) {
                override fun getPlaceholderText(): String? {
                    return "("
                }
            })

            descriptors.add(object : FoldingDescriptor(parent,
                    rPar.textRange, group) {
                override fun getPlaceholderText(): String? {
                    return ")"
                }
            })
        }
    }

    private fun foldSlimArrowFunction(node: ASTNode, descriptors: MutableList<in FoldingDescriptor>) {
        val group = FoldingGroup.newGroup("slim-arrow function")
        val params = node.findChildByType(PARAMETER_LISTS)
        val lPar = params?.findChildByType(LPAR)
        val rPar = params?.findChildByType(RPAR)
        val block = node.findChildByType(BLOCK_STATEMENT)
        val functionKeyword = node.findChildByType(FUNCTION_KEYWORD)
        val argumentsAreOnlyWhiteSpaces = params?.getChildren(TokenSet.ANY)
                ?.all {
                    it.elementType == WHITE_SPACE ||
                            it.elementType == LPAR ||
                            it.elementType == RPAR
                }
        val argumentsContainComma = params?.getChildren(TokenSet.ANY)
                ?.any {
                    it.elementType == COMMA
                }
        val doParenthesis = argumentsAreOnlyWhiteSpaces == true || argumentsContainComma == true

        if (lPar != null && rPar != null && block != null && functionKeyword != null) {
            val start = functionKeyword.startOffset
            descriptors.add(object : FoldingDescriptor(node,
                    TextRange(start, lPar.textRange.endOffset), group) {
                override fun getPlaceholderText(): String? {
                    return if (doParenthesis) {
                        "("
                    } else {
                        ""
                    }
                }
            })
            descriptors.add(object : FoldingDescriptor(node,
                    TextRange(rPar.startOffset, block.startOffset), group) {
                override fun getPlaceholderText(): String? {
                    return if (doParenthesis) {
                        ") -> "
                    } else {
                        " -> "
                    }
                }
            })
        }
    }

    override fun isRegionCollapsedByDefault(node: ASTNode): Boolean {
        return true
    }

    override fun getLanguagePlaceholderText(node: ASTNode, range: TextRange): String {
        return "() => { $range }"
    }

    companion object {
        private const val JS_PROPERTY = "JS:PROPERTY"
        private const val JS_LITERAL_EXPRESSION = "JS:LITERAL_EXPRESSION"
        private const val JS_CALL_EXPRESSION = "JS:CALL_EXPRESSION"
    }
}
