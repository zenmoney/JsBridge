package app.zenmoney.jsbridge

/** Prepares a Script completion value without compiling strings in the page (eval/Function). */
internal val jsWebViewAcornScriptWrapperSource =
    """
    (function () {
        let parse;
        return function (source, requestId, transformHandle) {
            if (!parse) parse = ($jsWebViewAcornFactorySource)();
            // Match the existing block-scoped evaluation, including global var declarations.
            source = "{\n" + source + "\n}";
            const names = [];
            const tree = parse(source, {
                ecmaVersion: "latest",
                onToken(token) { if (typeof token.value === "string") names.push(token.value); }
            });
            let name = "__appZenmoneyCompletion";
            while (source.includes(name) || names.some(value => value.includes(name))) name += "_";
            const value = name + "Value";
            const error = name + "Error";
            const edits = [];
            let usesWith = false;
            let sequence = 0;
            function insert(position, text) { edits.push({ position, text, sequence: sequence++ }); }
            function reset(node, body) {
                insert(node.start, "{ " + value + " = void 0; ");
                body();
                insert(node.end, "\n}");
            }
            function visit(node, labelled) {
                switch (node.type) {
                    case "ExpressionStatement":
                        // Use statement bounds to retain parentheses, regexes, comments and ASI.
                        insert(node.start, value + " = (");
                        insert(node.expression.end, ")");
                        break;
                    case "BlockStatement":
                        node.body.forEach(child => visit(child));
                        break;
                    case "LabeledStatement": {
                        if (labelled) {
                            visit(node.body, true);
                            break;
                        }
                        let body = node.body;
                        while (body.type === "LabeledStatement") body = body.body;
                        // A continue label must still directly label its iteration statement.
                        if (/^(For|While|DoWhile)/.test(body.type)) {
                            reset(node, () => visit(node.body, true));
                        } else {
                            visit(node.body);
                        }
                        break;
                    }
                    case "IfStatement":
                        reset(node, () => {
                            visit(node.consequent);
                            if (node.alternate) visit(node.alternate);
                        });
                        break;
                    case "ForStatement":
                    case "ForInStatement":
                    case "ForOfStatement":
                    case "WhileStatement":
                    case "DoWhileStatement":
                        if (labelled) visit(node.body);
                        else reset(node, () => visit(node.body));
                        break;
                    case "SwitchStatement":
                        reset(node, () => node.cases.forEach(branch => branch.consequent.forEach(child => visit(child))));
                        break;
                    case "WithStatement":
                        usesWith = true;
                        reset(node, () => visit(node.body));
                        break;
                    case "TryStatement":
                        reset(node, () => {
                            visit(node.block);
                            if (node.handler) {
                                insert(node.handler.body.start + 1, value + " = void 0;");
                                visit(node.handler.body);
                            }
                            if (node.finalizer) {
                                const saved = name + "Saved" + sequence;
                                const normal = saved + "Normal";
                                insert(node.finalizer.start + 1,
                                    "let " + saved + " = " + value + ", " + normal + " = false; " +
                                    value + " = void 0; try {\n");
                                visit(node.finalizer);
                                insert(node.finalizer.end - 1,
                                    "\n" + normal + " = true; } finally { if (" + normal + ") " + value + " = " + saved + "; }");
                            }
                        });
                        break;
                    // Declarations and empty/abrupt statements do not produce a new value.
                    // Never walk expressions, function bodies or class bodies.
                }
            }
            visit(tree.body[0]);
            // A with object (especially a Proxy) can shadow any outer lexical identifier.
            // Script-level this cannot be shadowed. Use a temporary non-enumerable property
            // only for these scripts, and remove it on both normal and abrupt completion.
            let stateKey = name + "State" + requestId;
            if (usesWith) while (stateKey in globalThis) stateKey += "_";
            const state = usesWith ? "this[" + JSON.stringify(stateKey) + "]" : value;
            edits.sort((a, b) => a.position - b.position || a.sequence - b.sequence);
            let result = "", offset = 0;
            for (const edit of edits) {
                result += source.slice(offset, edit.position) + edit.text.split(value).join(state);
                offset = edit.position;
            }
            result += source.slice(offset);
            const dispatch = "globalThis.$JS_WEB_VIEW_BRIDGE_OBJECT.dispatch";
            const complete = '${JsWebViewProtocolCode.COMMAND_COMPLETE_EVALUATION.toJson()}';
            const initialize = usesWith
                ? "Object.defineProperty(this, " + JSON.stringify(stateKey) + ", { value: void 0, writable: true, configurable: true });\n"
                : "";
            const cleanup = usesWith ? " finally { delete " + state + "; }" : "";
            const transform = typeof transformHandle === "number" ? "," + transformHandle : "";
            return "{ let " + value + "; try {\n" + initialize + result + "\n" +
                dispatch + "([" + complete + ", false, " + state + transform + "]," + requestId + ");\n" +
                "} catch (" + error + ") { " + dispatch + "([" + complete + ", true, " + error + "]," + requestId + "); }" + cleanup + " }";
        };
    })()
    """.trimIndent()
