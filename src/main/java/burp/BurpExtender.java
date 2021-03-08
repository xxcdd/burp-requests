package burp;

import java.util.*;
import java.awt.datatransfer.*;
import java.awt.event.*;
import java.awt.Toolkit;
import java.io.UnsupportedEncodingException;
import javax.swing.JMenuItem;

import mjson.Json;

public class BurpExtender implements IBurpExtender, IContextMenuFactory, ClipboardOwner
{
    private IExtensionHelpers helpers;

    private final static String NAME = "Copy as requests pycode";
    private final static String SESSION_MENU_ITEM = NAME + " with session object";
    private final static String[] PYTHON_ESCAPE = new String[256];
    private final static String SESSION_VAR = "session";

    static {
        for (int i = 0x00; i <= 0xFF; i++) PYTHON_ESCAPE[i] = String.format("\\x%02x", i);
        for (int i = 0x20; i < 0x80; i++) PYTHON_ESCAPE[i] = String.valueOf((char)i);
        PYTHON_ESCAPE['\n'] = "\\n";
        PYTHON_ESCAPE['\r'] = "\\r";
        PYTHON_ESCAPE['\t'] = "\\t";
        PYTHON_ESCAPE['"'] = "\\\"";
        PYTHON_ESCAPE['\\'] = "\\\\";
    }

    @Override
    public void registerExtenderCallbacks(IBurpExtenderCallbacks callbacks)
    {
        helpers = callbacks.getHelpers();
        callbacks.setExtensionName(NAME);
        callbacks.registerContextMenuFactory(this);
    }

    @Override
    public List<JMenuItem> createMenuItems(IContextMenuInvocation invocation) {
        final IHttpRequestResponse[] messages = invocation.getSelectedMessages();
        if (messages == null || messages.length == 0) return null;
        JMenuItem i1 = new JMenuItem(NAME);
        i1.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                copyMessages(messages, false);
            }
        });
        JMenuItem i2 = new JMenuItem(SESSION_MENU_ITEM);
        i2.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                copyMessages(messages, true);
            }
        });
        return Arrays.asList(i1, i2);
    }

    private enum BodyType {JSON, DATA};

    private void copyMessages(IHttpRequestResponse[] messages, boolean withSessionObject) {
        StringBuilder py = new StringBuilder("import requests");
        py.append("\n" + "from requests.packages import urllib3");
        py.append("\n" + "urllib3.disable_warnings()");
        py.append("\n" + "proxies = {}");
        py.append("\n" + "# proxies = {\"http\": \"http://127.0.0.1:8080\", \"https\": \"http://127.0.0.1:8080\"}");
        String requestsMethodPrefix =
                "\n" + "resp = " + (withSessionObject ? SESSION_VAR : "requests") + ".";
        int i = 0;

        if (withSessionObject) {
            py.append("\n\n" + SESSION_VAR + " = requests.session()");
        }

        for (IHttpRequestResponse message : messages) {
            IRequestInfo ri = helpers.analyzeRequest(message);
            byte[] req = message.getRequest();
            String prefix = "burp" + i++ + "_";
            py.append("\n\n").append(prefix).append("url = \"");
            py.append(escapeQuotes(ri.getUrl().toString()));
            py.append('"');
            List<String> headers = ri.getHeaders();
            boolean cookiesExist = processCookies(prefix, py, headers);
            py.append('\n').append(prefix).append("headers = {\n");
            processHeaders(py, headers);
            py.append("\n}");
            BodyType bodyType = processBody(prefix, py, req, ri);
            py.append(requestsMethodPrefix);
            py.append(ri.getMethod().toLowerCase());
            py.append('(').append(prefix).append("url, headers=");
            py.append(prefix).append("headers");
            if (cookiesExist) py.append(", cookies=").append(prefix).append("cookies");
            if (bodyType != null) {
                String kind = bodyType.toString().toLowerCase();
                py.append(", ").append(kind).append('=').append(prefix).append(kind);
            }
            py.append(", proxies=proxies, verify=False)");
        }

        Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(py.toString()), this);
    }

    private static boolean processCookies(String prefix, StringBuilder py,
                                          List<String> headers) {
        ListIterator<String> iter = headers.listIterator();
        boolean cookiesExist = false;
        while (iter.hasNext()) {
            String header = iter.next();
            if (!header.toLowerCase().startsWith("cookie:")) continue;
            iter.remove();
            for (String cookie : header.substring(8).split("; ?")) {
                if (cookiesExist) {
                    py.append(", \"");
                } else {
                    cookiesExist = true;
                    py.append('\n').append(prefix).append("cookies = {\"");
                }
                String[] parts = cookie.split("=", 2);
                py.append(escapeQuotes(parts[0]));
                py.append("\": \"");
                py.append(escapeQuotes(parts[1]));
                py.append('"');
            }
        }
        if (cookiesExist) py.append('}');
        return cookiesExist;
    }

    private static final Collection<String> IGNORE_HEADERS = Arrays.asList("host:", "content-length:");

    private static void processHeaders(StringBuilder py, List<String> headers) {
        boolean firstHeader = true;
        header_loop:
        for (String header : headers) {
            String lowerCaseHeader = header.toLowerCase();
            for (String headerToIgnore : IGNORE_HEADERS) {
                if (lowerCaseHeader.startsWith(headerToIgnore)) continue header_loop;
            }
            header = escapeQuotes(header);
            int colonPos = header.indexOf(':');
            if (colonPos == -1) continue;
            if (firstHeader) {
                firstHeader = false;
                py.append("    \"");
            } else {
                py.append(",\n    \"");
            }
            py.append(header, 0, colonPos);
            py.append("\": \"");
            py.append(header, colonPos + 2, header.length());
            py.append('"');
        }
    }

    private BodyType processBody(String prefix, StringBuilder py,
                                 byte[] req, IRequestInfo ri) {
        int bo = ri.getBodyOffset();
        if (bo >= req.length - 2) return null;
        py.append('\n').append(prefix);
        byte contentType = ri.getContentType();
        if (contentType == IRequestInfo.CONTENT_TYPE_JSON) {
            try {
                Json root = Json.read(byteSliceToString(req, bo, req.length));
                py.append("json=");
                escapeJson(root, py);
                return BodyType.JSON;
            } catch (Exception e) {
                // not valid JSON, treat it like any other kind of data
            }
        }
        py.append("data = ");
        if (contentType == IRequestInfo.CONTENT_TYPE_URL_ENCODED) {
            py.append("{\n    ");
            boolean firstKey = true;
            int keyStart = bo, keyEnd = -1;
            for (int pos = bo; pos < req.length; pos++) {
                byte b = req[pos];
                if (keyEnd == -1) {
                    if (b == (byte)'=') {
                        if (pos == req.length - 1) {
                            if (!firstKey) py.append(",\n    ");
                            escapeUrlEncodedBytes(req, py, keyStart, pos);
                            py.append(": ''");
                        } else {
                            keyEnd = pos;
                        }
                    }
                } else if (b == (byte)'&' || pos == req.length - 1) {
                    if (firstKey) firstKey = false; else py.append(",\n    ");
                    escapeUrlEncodedBytes(req, py, keyStart, keyEnd);
                    py.append(": ");
                    escapeUrlEncodedBytes(req, py, keyEnd + 1,
                            pos == req.length - 1 ? req.length : pos);
                    keyEnd = -1;
                    keyStart = pos + 1;
                }
            }
            py.append("\n}");
        } else {
            escapeBytes(req, py, bo, req.length);
        }
        return BodyType.DATA;
    }

    private static String escapeQuotes(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    private void escapeUrlEncodedBytes(byte[] input, StringBuilder output,
                                       int start, int end) {
        if (end > start) {
            byte[] dec = helpers.urlDecode(Arrays.copyOfRange(input, start, end));
            escapeBytes(dec, output, 0, dec.length);
        } else {
            output.append("''");
        }
    }

    private static final String PYTHON_TRUE = "True", PYTHON_FALSE = "False", PYTHON_NULL = "None";

    private static void escapeJson(Json node, StringBuilder output) {
        if (node.isObject()) {
            output.append('{');
            Map<String, Json> tm = new TreeMap(String.CASE_INSENSITIVE_ORDER);
            tm.putAll(node.asJsonMap());
            final Iterator<Map.Entry<String, Json>> iter = tm.entrySet().iterator();
            if (iter.hasNext()) {
                appendIteratedEntry(iter, output);
                while (iter.hasNext()) {
                    output.append(", ");
                    appendIteratedEntry(iter, output);
                }
            }
            output.append('}');
        } else if (node.isArray()) {
            output.append('[');
            final Iterator<Json> iter = node.asJsonList().iterator();
            if (iter.hasNext()) {
                escapeJson(iter.next(), output);
                while (iter.hasNext()) {
                    output.append(", ");
                    escapeJson(iter.next(), output);
                }
            }
            output.append(']');
        } else if (node.isString()) {
            escapeString(node.asString(), output);
        } else if (node.isBoolean()) {
            output.append(node.asBoolean() ? PYTHON_TRUE : PYTHON_FALSE);
        } else if (node.isNull()) {
            output.append(PYTHON_NULL);
        } else if (node.isNumber()) {
            output.append(node.asString());
        }
    }

    private static void appendIteratedEntry(Iterator<Map.Entry<String, Json>> iter, StringBuilder output) {
        final Map.Entry<String, Json> e = iter.next();
        escapeString(e.getKey(), output);
        output.append(": ");
        escapeJson(e.getValue(), output);
    }

    private static String byteSliceToString(byte[] input, int from, int till) {
        try {
            return new String(input, from, till - from, "ISO-8859-1");
        } catch (UnsupportedEncodingException uee) {
            throw new RuntimeException("All JVMs must support ISO-8859-1");
        }
    }

    private static void escapeString(String input, StringBuilder output) {
        output.append('"');
        int length = input.length();
        for (int pos = 0; pos < length; pos++) {
            output.append(PYTHON_ESCAPE[input.charAt(pos) & 0xFF]);
        }
        output.append('"');
    }

    private static void escapeBytes(byte[] input, StringBuilder output,
                                    int start, int end) {
        output.append('"');
        for (int pos = start; pos < end; pos++) {
            output.append(PYTHON_ESCAPE[input[pos] & 0xFF]);
        }
        output.append('"');
    }

    @Override
    public void lostOwnership(Clipboard aClipboard, Transferable aContents) {}
}
