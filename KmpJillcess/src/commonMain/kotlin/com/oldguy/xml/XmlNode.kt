package com.oldguy.xml

data class Attribute(
    val name: String,
    val value: String
)

class Node(
    val name: String,
    val value : String,
    val attributes: Map<String, Attribute>,
    val children: List<Node>
) {
    fun find(name: String): Node? {
        if (this.name == name)
            return this
        children.forEach {
            val result = it.find(name)
            if (result != null)
                return result
        }
        return null
    }

    fun findAttribute(name: String): Attribute?
    {
        return attributes[name]
    }
}
