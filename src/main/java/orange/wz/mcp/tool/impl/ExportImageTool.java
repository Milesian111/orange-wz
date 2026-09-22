package orange.wz.mcp.tool.impl;

import orange.wz.mcp.service.impl.PortableImageExporter;
import orange.wz.mcp.service.McpWorkspaceService;
import orange.wz.mcp.session.McpSessionManager;
import orange.wz.mcp.tool.support.BaseSessionTool;
import java.util.List;
import java.util.Map;
import static orange.wz.mcp.tool.support.ToolSchemas.*;

public final class ExportImageTool extends BaseSessionTool {
    private final PortableImageExporter exporter;
    public ExportImageTool(McpSessionManager sessions, McpWorkspaceService service) {
        super(sessions, "Export standalone IMG with target encryption, materialized canvas links, ARGB8888 pixels, optional resource-free server XML and full read-back verification. Refuses existing outputs.", objectSchema(
                Map.of("rootPath", stringSchema(), "nodePath", stringSchema(), "filePath", stringSchema(),
                        "xmlPath", stringSchema(), "targetKey", keySchema(), "sourceKey", keySchema(), "sourceDataRoot", stringSchema(), "legacyCompatible", booleanSchema()),
                List.of("rootPath", "filePath", "targetKey", "sourceKey", "sourceDataRoot")));
        this.exporter = new PortableImageExporter(service);
    }
    @Override public String name() { return "export_image"; }
    @Override public Map<String, Object> invoke(Map<String, Object> params) {
        var session = session(params);
        session.lock();
        try { return exporter.export(session, params); }
        finally { session.unlock(); }
    }
}
