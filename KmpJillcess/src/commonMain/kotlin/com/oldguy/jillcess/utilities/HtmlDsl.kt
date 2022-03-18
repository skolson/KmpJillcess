package com.oldguy.jillcess.utilities

interface Element {
    fun render(builder: StringBuilder, indent: String)
}

class TextElement(val text: String) : Element {
    override fun render(builder: StringBuilder, indent: String) {
        builder.append("$indent$text\n")
    }
}

@DslMarker
annotation class HtmlTagMarker

@HtmlTagMarker
abstract class Tag(val name: String) : Element {
    val children = arrayListOf<Element>()
    val attributes = hashMapOf<String, String>()

    protected suspend fun <T : Element> initTag(tag: T, init: suspend T.() -> Unit): T {
        tag.init()
        children.add(tag)
        return tag
    }

    override fun render(builder: StringBuilder, indent: String) {
        builder.append("$indent<$name${renderAttributes()}>\n")
        for (c in children) {
            c.render(builder, indent + "  ")
        }
        builder.append("$indent</$name>\n")
    }

    private fun renderAttributes(): String {
        val builder = StringBuilder()
        for ((attr, value) in attributes) {
            builder.append(" $attr=\"$value\"")
        }
        return builder.toString()
    }

    override fun toString(): String {
        val builder = StringBuilder()
        render(builder, "")
        return builder.toString()
    }
}

abstract class TagWithText(name: String) : Tag(name) {
    operator fun String.unaryPlus() {
        children.add(TextElement(this))
    }
}

class HTML : TagWithText("html") {
    suspend fun head(init: suspend Head.() -> Unit) = initTag(Head(), init)

    suspend fun body(init: suspend Body.() -> Unit) = initTag(Body(), init)
}

class Head : TagWithText("head") {
    suspend fun title(init: suspend Title.() -> Unit) = initTag(Title(), init)
    suspend fun style(init: suspend Style.() -> Unit) = initTag(Style(), init)
}

class Title : TagWithText("title")
class Style : TagWithText("style")

abstract class BodyTag(name: String) : TagWithText(name) {
    suspend fun b(init: suspend B.() -> Unit) = initTag(B(), init)
    suspend fun i(init: suspend I.() -> Unit) = initTag(I(), init)
    suspend fun p(init: suspend P.() -> Unit) = initTag(P(), init)
    suspend fun h1(init: suspend H1.() -> Unit) = initTag(H1(), init)
    suspend fun a(href: String, init: suspend A.() -> Unit) {
        val a = initTag(A(), init)
        a.href = href
    }

    suspend fun ul(init: suspend UL.() -> Unit) = initTag(UL(), init)
    suspend fun ol(type: String = "1", init: suspend OL.() -> Unit) {
        val t = initTag(OL(), init)
        t.type = type
    }

    suspend fun table(init: suspend TABLE.() -> Unit) = initTag(TABLE(), init)
}

class Body : BodyTag("body")
class B : BodyTag("b")
class I : BodyTag("i")
class P : BodyTag("p")
class H1 : BodyTag("h1")

class A : BodyTag("a") {
    var href: String
        get() = attributes["href"]!!
        set(value) {
            attributes["href"] = value
        }
}

class LI : BodyTag("li")

class UL : BodyTag("ul") {
    suspend fun li(init: LI.() -> Unit) = initTag(LI(), init)
}

class OL : BodyTag("ol") {
    var type: String
        get() = attributes["type"]!!
        set(value) {
            attributes["type"] = value
        }

    suspend fun li(init: LI.() -> Unit) = initTag(LI(), init)
}

class TH : BodyTag("th")
class TD : BodyTag("td")

class TR : BodyTag("tr") {
    suspend fun th(init: suspend TH.() -> Unit) = initTag(TH(), init)
    suspend fun td(init: suspend TD.() -> Unit) = initTag(TD(), init)
}

class TABLE : BodyTag("table") {
    suspend fun tr(init: suspend TR.() -> Unit) = initTag(TR(), init)
}

suspend fun html(init: suspend HTML.() -> Unit): HTML {
    val html = HTML()
    html.init()
    return html
}
