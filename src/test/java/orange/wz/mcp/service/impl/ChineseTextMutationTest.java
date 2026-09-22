package orange.wz.mcp.service.impl;

import orange.wz.mcp.session.McpSessionManager;
import orange.wz.mcp.session.McpSessionState;
import orange.wz.mcp.support.McpException;
import orange.wz.mcp.tool.impl.BatchUpdateNodesTool;
import orange.wz.mcp.tool.impl.MutateNodesTool;
import orange.wz.provider.WzImageFile;
import orange.wz.provider.properties.WzStringProperty;
import orange.wz.provider.tools.wzkey.WzKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static orange.wz.provider.WzAESConstant.DEFAULT_KEY;
import static orange.wz.provider.WzAESConstant.WZ_GMS_IV;
import static org.junit.jupiter.api.Assertions.*;

class ChineseTextMutationTest {
    @TempDir Path dir;
    private final McpSessionManager sessions = new McpSessionManager();
    private final McpSessionState session = sessions.createSession();
    private final DefaultMcpWorkspaceService service = new DefaultMcpWorkspaceService();

    private WzStringProperty createTextNode() {
        WzKey key = new WzKey();
        key.setName("test");
        key.setIv(WZ_GMS_IV);
        key.setUserKey(DEFAULT_KEY);
        service.createImg(session, dir.resolve("sample.img").toString(), key);
        WzImageFile image = (WzImageFile) session.getRoots().getFirst();
        WzStringProperty text = new WzStringProperty("name", "original", image, image);
        image.addChild(text);
        image.setChanged(false);
        return text;
    }

    private Map<String, Object> operation(String payload) {
        return Map.of("rootPath", dir.resolve("sample.img").toString(),
                "nodePath", "name", "op", "set_chinese_text", "textBase64", payload);
    }

    private String encode(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void singleAndBatchToolsPreserveMultilingualTextAndNewlines() {
        WzStringProperty node = createTextNode();
        String text = "中文名称\n描述包含 日本語 한국어 café 😀";
        Map<String, Object> request = new HashMap<>(operation(encode(text)));
        request.put("sessionId", session.getSessionId().toString());
        new MutateNodesTool(sessions, service).invoke(request);
        assertEquals(text, node.getValue());
        assertTrue(node.getWzImage().isChanged());

        new BatchUpdateNodesTool(sessions, service).invoke(Map.of(
                "sessionId", session.getSessionId().toString(),
                "operations", List.of(operation(encode("批量更新\r\n第二行")))));
        assertEquals("批量更新\r\n第二行", node.getValue());
    }

    @Test
    void emptyTextCanClearAnExistingDescription() {
        WzStringProperty node = createTextNode();
        service.batchUpdateNodes(session, List.of(operation("")));
        assertEquals("", node.getValue());
    }

    @Test
    void invalidBase64AndUtf8DoNotModifyTheNode() {
        WzStringProperty node = createTextNode();
        String invalidUtf8 = Base64.getEncoder().encodeToString(new byte[]{(byte) 0xc3, 0x28});
        for (String payload : List.of("%%%", invalidUtf8)) {
            assertThrows(McpException.class,
                    () -> service.batchUpdateNodes(session, List.of(operation(payload))));
            assertEquals("original", node.getValue());
            assertFalse(node.getWzImage().isChanged());
        }
    }

    @Test
    void ordinaryStringWritesRemainSupported() {
        WzStringProperty node = createTextNode();
        Map<String, Object> request = new HashMap<>(operation(""));
        request.put("op", "set_value");
        request.put("value", "直接写入中文");
        request.remove("textBase64");
        service.batchUpdateNodes(session, List.of(request));
        assertEquals("直接写入中文", node.getValue());
    }
}
