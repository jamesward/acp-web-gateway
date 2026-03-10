package com.jamesward.acpgateway.server

import com.jamesward.acpgateway.shared.Css
import com.jamesward.acpgateway.shared.Id
import kotlinx.html.*
import java.util.UUID

fun HTML.chatPage(
    agentName: String,
    sessionId: UUID? = null,
    debug: Boolean = false,
    dev: Boolean = false,
    agents: List<RegistryAgent> = emptyList(),
    currentAgentId: String? = null,
) {
    head {
        meta { charset = "utf-8" }
        meta { name = "viewport"; content = "width=device-width, initial-scale=1" }
        title { +"ACP Gateway - $agentName" }
        link { rel = "stylesheet"; href = "/styles.css" }
    }
    body {
        if (sessionId != null) {
            attributes["data-session-id"] = sessionId.toString()
        }
        if (debug) {
            attributes["data-debug"] = "true"
        }
        if (currentAgentId != null) {
            attributes["data-agent-id"] = currentAgentId
        }
        div(classes = Css.HEADER) {
            // Agent icon + name + swap button
            if (agents.isNotEmpty() && currentAgentId != null) {
                val current = agents.find { it.id == currentAgentId }
                if (current?.icon != null) {
                    img(classes = Css.AGENT_ICON_SM) {
                        src = current.icon
                        alt = current.name
                    }
                }
                span(classes = Css.AGENT_NAME_TEXT) {
                    id = Id.AGENT_INFO
                    +(current?.name ?: currentAgentId)
                }
                button(classes = Css.AGENT_SWAP_BTN_CLS) {
                    id = Id.AGENT_SWAP_BTN
                    type = ButtonType.button
                    title = "Switch agent"
                    +"⇄"
                }
            } else if (currentAgentId != null) {
                span(classes = Css.AGENT_NAME_TEXT) {
                    id = Id.AGENT_INFO
                    +currentAgentId
                }
            } else {
                span(classes = Css.AGENT_NAME_TEXT) {
                    id = Id.AGENT_INFO
                }
            }

            div(classes = "${Css.HEADER_CWD} ${Css.HIDDEN}") { id = Id.HEADER_CWD }

            if (dev) {
                button(classes = Css.RELOAD_BTN) {
                    id = Id.RELOAD_BTN
                    type = ButtonType.button
                    +"Reload"
                }
            }
        }
        div {
            id = Css.MESSAGES
        }
        div {
            attributes["style"] = "position: relative; flex-shrink: 0"
            button(classes = "${Css.SCROLL_BTN} ${Css.HIDDEN}") {
                id = Id.SCROLL_BTN
                type = ButtonType.button
                title = "Scroll to bottom"
                unsafe {
                    raw("""<svg xmlns="http://www.w3.org/2000/svg" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="6 9 12 15 18 9"></polyline></svg>""")
                }
            }
        }
        div(classes = "${Css.STATUS_WRAP} ${Css.HIDDEN}") {
            id = Id.TASK_STATUS_WRAP
            div(classes = Css.STATUS_TEXT) {
                id = Id.TASK_STATUS
            }
        }
        div(classes = Css.INPUT_BAR) {
            div(classes = "${Css.AUTOCOMPLETE} ${Css.HIDDEN}") {
                id = Id.AUTOCOMPLETE
            }
            div(classes = "${Css.FILE_PREVIEW} ${Css.HIDDEN}") {
                id = Id.FILE_PREVIEW
            }
            form(classes = Css.INPUT_FORM) {
                id = Id.PROMPT_FORM
                button(classes = Css.ATTACH_BTN) {
                    id = Id.ATTACH_BTN
                    type = ButtonType.button
                    title = "Attach files"
                    +"+"
                }
                input(classes = Css.HIDDEN) {
                    id = Id.FILE_INPUT
                    type = InputType.file
                    attributes["multiple"] = "true"
                }
                textArea(classes = Css.PROMPT_INPUT) {
                    id = Id.PROMPT_INPUT
                    attributes["rows"] = "3"
                    placeholder = "Send a message..."
                }
                div(classes = Css.BTN_GROUP) {
                    div {
                        attributes["style"] = "display: flex; align-items: center; gap: 8px"
                        button(classes = Css.SEND_BTN) {
                            id = Id.SEND_BTN
                            type = ButtonType.submit
                            +"Send"
                        }
                        if (debug) {
                            button(classes = "${Css.DIAGNOSE_BTN} ${Css.HIDDEN}") {
                                id = Id.DIAGNOSE_BTN
                                type = ButtonType.button
                                +"Diagnose"
                            }
                        }
                    }
                    label(classes = Css.SCREENSHOT_LABEL) {
                        input(classes = Css.SCREENSHOT_CHECK) {
                            id = Id.SCREENSHOT_TOGGLE
                            type = InputType.checkBox
                        }
                        +"Screenshot"
                    }
                }
            }
        }
        div(classes = "${Css.PERM_OVERLAY} ${Css.HIDDEN}") {
            id = Id.PERMISSION_DIALOG
            div(classes = Css.PERM_CARD) {
                id = Id.PERMISSION_CONTENT
            }
        }

        // Agent selector modal (visible when no agent selected, hidden otherwise)
        if (agents.isNotEmpty()) {
            val modalClasses = if (currentAgentId != null) {
                "${Css.AGENT_MODAL_OVERLAY} ${Css.HIDDEN}"
            } else {
                Css.AGENT_MODAL_OVERLAY
            }
            div(classes = modalClasses) {
                id = Id.AGENT_MODAL
                div(classes = Css.AGENT_MODAL_CARD) {
                    h2(classes = Css.AGENT_MODAL_TITLE) { +"Select an Agent" }
                    div(classes = Css.AGENT_TABLE) {
                        for (agent in agents) {
                            div(classes = Css.AGENT_ROW) {
                                attributes["data-agent-id"] = agent.id
                                if (agent.icon != null) {
                                    img(classes = Css.AGENT_ROW_ICON) {
                                        src = agent.icon
                                        alt = agent.name
                                    }
                                }
                                div(classes = Css.AGENT_ROW_INFO) {
                                    div(classes = Css.AGENT_ROW_NAME) { +agent.name }
                                    if (agent.description.isNotEmpty()) {
                                        div(classes = Css.AGENT_ROW_DESC) { +agent.description }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Agent loading overlay (hidden by default)
        div(classes = "${Css.AGENT_LOADING} ${Css.HIDDEN}") {
            id = Id.AGENT_LOADING
            +"Starting agent\u2026"
        }

        // idiomorph — lightweight DOM morph library (~3KB)
        script {
            unsafe {
                raw(IDIOMORPH_INLINE)
            }
        }
        script { src = "/static/web.js" }
    }
}

fun HTML.landingPage(agentName: String) {
    head {
        meta { charset = "utf-8" }
        meta { name = "viewport"; content = "width=device-width, initial-scale=1" }
        title { +"ACP Gateway - $agentName" }
        link { rel = "stylesheet"; href = "/styles.css" }
    }
    body {
        div {
            attributes["style"] = "display: flex; align-items: center; justify-content: center; height: 100vh; text-align: center"
            div {
                h1 { attributes["style"] = "font-size: 1.875rem; font-weight: bold; margin-bottom: 1.5rem"; +"ACP Gateway" }
                p { attributes["style"] = "color: #9ca3af"; +"Agent: $agentName" }
            }
        }
    }
}

// Minified idiomorph 0.3.0 — https://github.com/bigskysoftware/idiomorph
// PATCHED: createMorphContext deep-merges config.callbacks onto defaults so that
// callers can pass a partial callbacks object (e.g. only beforeNodeMorphed) without
// losing the other default callbacks. Stock idiomorph uses a single Object.assign
// which shallow-merges, replacing the entire callbacks object.
// Provides window.Idiomorph.morph(oldNode, newHtml, {morphStyle: 'innerHTML'})
private val IDIOMORPH_INLINE = """
var Idiomorph=(function(){"use strict";var _defaultCallbacks={beforeNodeAdded:()=>true,afterNodeAdded:()=>{},beforeNodeMorphed:()=>true,afterNodeMorphed:()=>{},beforeNodeRemoved:()=>true,afterNodeRemoved:()=>{},beforeAttributeUpdated:()=>true};function morph(oldNode,newContent,config){if(oldNode instanceof Document)oldNode=oldNode.documentElement;let ctx=createMorphContext(oldNode,newContent,config);return morphNormalizedContent(oldNode,ctx)}function morphNormalizedContent(oldNode,ctx){let newContent=ctx.newContent;let morphStyle=ctx.config.morphStyle;if(morphStyle==="innerHTML"){morphChildren(newContent,oldNode,ctx);return oldNode.children}else if(morphStyle==="outerHTML"||morphStyle==null){let bestMatch=findBestNodeMatch(newContent,oldNode,ctx);let previousSibling=oldNode.previousSibling;let nextSibling=oldNode.nextSibling;let morphedNode=morphOldNodeTo(oldNode,bestMatch,ctx);if(morphedNode!==oldNode&&oldNode.parentNode){if(ctx.config.callbacks.beforeNodeRemoved(oldNode)===false)return oldNode;oldNode.parentNode.removeChild(oldNode);ctx.config.callbacks.afterNodeRemoved(oldNode)}return morphedNode}}function ignoreValueOfActiveElement(possibleActiveElement,ctx){return ctx.ignoreActiveValue&&possibleActiveElement===document.activeElement&&possibleActiveElement!==document.body}function morphOldNodeTo(oldNode,newNode,ctx){if(ctx.config.callbacks.beforeNodeMorphed(oldNode,newNode)===false)return oldNode;if(oldNode instanceof HTMLTemplateElement){oldNode=oldNode.content;newNode=newNode.content}syncNodeFrom(newNode,oldNode,ctx);if(!ignoreValueOfActiveElement(oldNode,ctx)){morphChildren(newNode,oldNode,ctx)}ctx.config.callbacks.afterNodeMorphed(oldNode,newNode);return oldNode}function morphChildren(newParent,oldParent,ctx){let nextNewChild=newParent.firstChild;let insertionPoint=oldParent.firstChild;let newChild;while(nextNewChild){newChild=nextNewChild;nextNewChild=newChild.nextSibling;if(insertionPoint==null){if(ctx.config.callbacks.beforeNodeAdded(newChild)===false)continue;oldParent.appendChild(newChild);ctx.config.callbacks.afterNodeAdded(newChild);continue}if(isIdSetMatch(newChild,insertionPoint,ctx)){morphOldNodeTo(insertionPoint,newChild,ctx);insertionPoint=insertionPoint.nextSibling;continue}let idSetMatch=findIdSetMatch(newParent,oldParent,newChild,insertionPoint,ctx);if(idSetMatch){insertionPoint=removeNodesBetween(insertionPoint,idSetMatch,ctx);morphOldNodeTo(idSetMatch,newChild,ctx);insertionPoint=insertionPoint?.nextSibling;continue}let softMatch=findSoftMatch(newParent,oldParent,newChild,insertionPoint,ctx);if(softMatch){insertionPoint=removeNodesBetween(insertionPoint,softMatch,ctx);morphOldNodeTo(softMatch,newChild,ctx);insertionPoint=insertionPoint?.nextSibling;continue}if(ctx.config.callbacks.beforeNodeAdded(newChild)===false)continue;oldParent.insertBefore(newChild,insertionPoint);ctx.config.callbacks.afterNodeAdded(newChild)}while(insertionPoint){let tempNode=insertionPoint;insertionPoint=insertionPoint.nextSibling;removeNode(tempNode,ctx)}}function syncNodeFrom(from,to,ctx){let type=from.nodeType;if(type===1){for(const fromAttribute of from.attributes){if(to.getAttribute(fromAttribute.name)!==fromAttribute.value)to.setAttribute(fromAttribute.name,fromAttribute.value)}for(const toAttribute of[...to.attributes]){if(!from.hasAttribute(toAttribute.name))to.removeAttribute(toAttribute.name)}if(!ignoreValueOfActiveElement(to,ctx)){syncInputValue(from,to,ctx)}}if(type===8||type===3){if(to.nodeValue!==from.nodeValue)to.nodeValue=from.nodeValue}if(!ignoreValueOfActiveElement(to,ctx)){}}function syncInputValue(from,to,ctx){if(from instanceof HTMLInputElement&&to instanceof HTMLInputElement&&from.type!=="file"){let fromValue=from.value;let toValue=to.value;syncBooleanAttribute(from,to,"checked",ctx);syncBooleanAttribute(from,to,"disabled",ctx);if(!from.hasAttribute("value")){to.value="";to.removeAttribute("value")}else if(fromValue!==toValue){to.setAttribute("value",fromValue);to.value=fromValue}}else if(from instanceof HTMLOptionElement){syncBooleanAttribute(from,to,"selected",ctx)}else if(from instanceof HTMLTextAreaElement&&to instanceof HTMLTextAreaElement){let fromValue=from.value;let toValue=to.value;if(fromValue!==toValue){to.value=fromValue}if(to.firstChild&&to.firstChild.nodeValue!==fromValue)to.firstChild.nodeValue=fromValue}}function syncBooleanAttribute(from,to,attributeName,ctx){if(from[attributeName]!==to[attributeName]){to[attributeName]=from[attributeName];if(from[attributeName])to.setAttribute(attributeName,"");else to.removeAttribute(attributeName)}}function removeNode(tempNode,ctx){removeIdsFromConsideration(ctx,tempNode);if(ctx.config.callbacks.beforeNodeRemoved(tempNode)===false)return;tempNode.remove();ctx.config.callbacks.afterNodeRemoved(tempNode)}function isIdSetMatch(node1,node2,ctx){if(node1==null||node2==null)return false;if(node1.nodeType===node2.nodeType&&node1.tagName===node2.tagName){if(node1.id!==""&&node1.id===node2.id)return true;return getIdIntersectionCount(ctx,node1,node2)>0}return false}function findIdSetMatch(newContent,oldParent,newChild,insertionPoint,ctx){let newChildIdSet=getIdSet(ctx,newChild);let potentialMatch=null;if(newChildIdSet&&newChildIdSet.size>0){let potentialMatch2=insertionPoint;while(potentialMatch2){if(isIdSetMatch(newChild,potentialMatch2,ctx))return potentialMatch2;potentialMatch2=potentialMatch2.nextSibling}}return null}function findSoftMatch(newContent,oldParent,newChild,insertionPoint,ctx){let potentialSoftMatch=insertionPoint;let nextSibling=newChild.nextSibling;let siblingsChecked=0;while(potentialSoftMatch){if(isSoftMatch(potentialSoftMatch,newChild))return potentialSoftMatch;potentialSoftMatch=potentialSoftMatch.nextSibling;siblingsChecked++;if(siblingsChecked>=20)break}return null}function isSoftMatch(oldNode,newNode){if(oldNode.nodeType!==newNode.nodeType)return false;if(oldNode.tagName!==newNode.tagName)return false;if(oldNode.id!==""&&oldNode.id!==newNode.id)return false;if(oldNode.id===""&&newNode.id!=="")return false;return true}function removeNodesBetween(startInclusive,endExclusive,ctx){while(startInclusive!==endExclusive){let tempNode=startInclusive;startInclusive=startInclusive.nextSibling;removeNode(tempNode,ctx)}return endExclusive}function findBestNodeMatch(newContent,oldNode,ctx){let currentElement;currentElement=newContent.firstChild;let bestElement=currentElement;let score=0;while(currentElement){let newScore=scoreElement(currentElement,oldNode,ctx);if(newScore>score){bestElement=currentElement;score=newScore}currentElement=currentElement.nextSibling}return bestElement}function scoreElement(node1,node2,ctx){if(isSoftMatch(node1,node2)){return 0.5+getIdIntersectionCount(ctx,node1,node2)}return 0}function createMorphContext(oldNode,newContent,config){var _cb=config&&config.callbacks;config=Object.assign({morphStyle:"outerHTML",callbacks:Object.assign({},_defaultCallbacks),ignoreActive:false,ignoreActiveValue:false,head:{style:"merge"}},config);if(_cb)config.callbacks=Object.assign({},_defaultCallbacks,_cb);let newDoc=parseContent(newContent);return{config:config,newContent:newDoc,idMap:createIdMap(oldNode,newDoc),deadIds:new Set(),ignoreActiveValue:config.ignoreActiveValue||config.ignoreActive}}function getIdIntersectionCount(ctx,node1,node2){let dominated=new Set();let dominated2=new Set();populateIdSet(node1,dominated);populateIdSet(node2,dominated2);let dominated3=new Set();for(let val of dominated){if(dominated2.has(val)&&!ctx.deadIds.has(val))dominated3.add(val)}return dominated3.size}function getIdSet(ctx,node){let dominated=new Set();populateIdSet(node,dominated);return dominated}function populateIdSet(node,idSet){if(node.nodeType===1){let id=node.id;if(id&&id!=="")idSet.add(id);for(let child of node.children)populateIdSet(child,idSet)}}function createIdMap(oldContent,newContent){let idMap={old:new Map(),new:new Map()};populateIdMapForNode(oldContent,idMap.old,"");populateIdMapForNode(newContent,idMap.new,"");return idMap}function populateIdMapForNode(node,idMap,parentId){if(node.nodeType===1){let id=node.id;if(id&&id!==""){let current=idMap.get(id);if(current==null){idMap.set(id,{element:node,count:1,parentId:parentId})}else{current.count++}}for(let child of node.children)populateIdMapForNode(child,idMap,id||parentId)}}function removeIdsFromConsideration(ctx,node){populateIdSet(node,ctx.deadIds)}function parseContent(newContent){if(typeof newContent!=='string')return newContent;var t=document.createElement('template');t.innerHTML=newContent;return t.content}return{morph:morph}})();
""".trimIndent()
