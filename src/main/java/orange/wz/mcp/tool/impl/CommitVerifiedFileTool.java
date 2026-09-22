package orange.wz.mcp.tool.impl;

import orange.wz.mcp.session.McpSessionManager;
import orange.wz.mcp.tool.support.BaseSessionTool;
import orange.wz.mcp.tool.support.ToolParamHelper;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import static orange.wz.mcp.tool.support.ToolSchemas.*;

public final class CommitVerifiedFileTool extends BaseSessionTool {
    public CommitVerifiedFileTool(McpSessionManager sessions) {
        super(sessions, "Commit a verified candidate file to a target with a timestamped backup. Refuses hash mismatch and preserves the target backup.", objectSchema(
                Map.of("candidatePath",stringSchema(),"targetPath",stringSchema(),"expectedSha256",stringSchema(),"backupPath",stringSchema()),
                List.of("candidatePath","targetPath","expectedSha256","backupPath")));
    }
    @Override public String name(){return "commit_verified_file";}
    @Override public Map<String,Object> invoke(Map<String,Object> p){
        Path candidate=Path.of(ToolParamHelper.requireString(p,"candidatePath")).toAbsolutePath().normalize();
        Path target=Path.of(ToolParamHelper.requireString(p,"targetPath")).toAbsolutePath().normalize();
        Path backup=Path.of(ToolParamHelper.requireString(p,"backupPath")).toAbsolutePath().normalize();
        try {
            if(!Files.isRegularFile(candidate)) throw new IllegalStateException("candidate missing");
            String expected=ToolParamHelper.requireString(p,"expectedSha256").toLowerCase();
            String actual=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(candidate)));
            if(!actual.equals(expected)) throw new IllegalStateException("candidate hash mismatch");
            Files.createDirectories(backup.getParent());
            if(Files.exists(target)) { if(Files.exists(backup)) throw new IllegalStateException("backup already exists"); Files.copy(target,backup); }
            Files.createDirectories(target.getParent());
            Files.move(candidate,target,StandardCopyOption.REPLACE_EXISTING);
            return Map.of("committed",true,"targetPath",target.toString(),"backupPath",backup.toString(),"sha256",actual);
        } catch(Exception e){throw new RuntimeException("Commit failed: "+e.getMessage(),e);}
    }
}
