package orange.wz.mcp.tool.impl;

import orange.wz.mcp.session.McpSessionManager;
import orange.wz.mcp.tool.support.BaseSessionTool;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static orange.wz.mcp.tool.support.ToolSchemas.*;

public final class CommitVerifiedFilesTool extends BaseSessionTool {
    public CommitVerifiedFilesTool(McpSessionManager s){super(s,"Commit a batch of verified candidate files with backups and hash checks.",objectSchema(Map.of("files",arraySchema(objectSchema(Map.of("candidatePath",stringSchema(),"targetPath",stringSchema(),"backupPath",stringSchema(),"sha256",stringSchema()),List.of("candidatePath","targetPath","backupPath","sha256")))),List.of("files")));}
    @Override public String name(){return "commit_verified_files";}
    @Override @SuppressWarnings("unchecked") public Map<String,Object> invoke(Map<String,Object> p){
        Object raw=p.get("files"); if(!(raw instanceof List<?> list))throw new IllegalArgumentException("files must be array");
        List<Map<String,Object>> done=new ArrayList<>();
        try {
            for(Object item:list){Map<String,Object> f=(Map<String,Object>)item;Path c=Path.of(String.valueOf(f.get("candidatePath"))).toAbsolutePath().normalize();Path t=Path.of(String.valueOf(f.get("targetPath"))).toAbsolutePath().normalize();Path b=Path.of(String.valueOf(f.get("backupPath"))).toAbsolutePath().normalize();
                if(!Files.isRegularFile(c))throw new IllegalStateException("candidate missing: "+c);String actual=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(c)));if(!actual.equalsIgnoreCase(String.valueOf(f.get("sha256"))))throw new IllegalStateException("hash mismatch: "+c);Files.createDirectories(b.getParent());if(Files.exists(t)){if(Files.exists(b))throw new IllegalStateException("backup exists: "+b);Files.copy(t,b);}Files.createDirectories(t.getParent());Files.move(c,t,StandardCopyOption.REPLACE_EXISTING);done.add(Map.of("targetPath",t.toString(),"backupPath",b.toString(),"sha256",actual));}
            return Map.of("committed",done.size(),"files",done);
        } catch(Exception e){throw new RuntimeException("Batch commit failed after "+done.size()+" files: "+e.getMessage(),e);}
    }
}
