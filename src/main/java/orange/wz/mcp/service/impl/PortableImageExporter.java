package orange.wz.mcp.service.impl;

import orange.wz.mcp.service.McpWorkspaceService;
import orange.wz.mcp.session.McpSessionState;
import orange.wz.mcp.support.McpException;
import orange.wz.mcp.tool.support.ToolParamHelper;
import orange.wz.provider.*;
import orange.wz.provider.properties.*;
import orange.wz.provider.tools.*;
import orange.wz.provider.tools.wzkey.WzKey;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

public final class PortableImageExporter {
    private final McpWorkspaceService service;
    private final Map<String, Map<String, WzImage>> linkIndexes = new HashMap<>();
    private Path dataRoot;
    private WzKey sourceKey;
    private int canvasCount;
    private int linkCount;

    public PortableImageExporter(McpWorkspaceService service) { this.service = service; }

    public synchronized Map<String, Object> export(McpSessionState session, Map<String, Object> params) {
        WzObject obj = service.findNode(session, ToolParamHelper.getNodeReference(params), true);
        if (!(obj instanceof WzImage source)) throw new McpException("Source must be an IMG");
        if (source.getStatus() != WzFileStatus.PARSE_SUCCESS && !source.parse()) throw new McpException("Cannot parse source IMG");
        Path output = Path.of(ToolParamHelper.requireString(params, "filePath")).toAbsolutePath().normalize();
        String xml = ToolParamHelper.getString(params, "xmlPath", "");
        Path xmlPath = xml.isBlank() ? null : Path.of(xml).toAbsolutePath().normalize();
        if (!output.toString().endsWith(".img")) throw new McpException("Output must end with .img");
        if (Files.exists(output) || (xmlPath != null && Files.exists(xmlPath))) throw new McpException("Refusing to overwrite an existing output");
        dataRoot = Path.of(ToolParamHelper.requireString(params, "sourceDataRoot")).toAbsolutePath().normalize();
        sourceKey = ToolParamHelper.getWzKey(params, "sourceKey");
        WzKey targetKey = ToolParamHelper.getWzKey(params, "targetKey");
        String resourceName = output.getFileName().toString();
        if (xmlPath != null && xmlPath.getFileName().toString().endsWith(".img.xml")) {
            String xmlName = xmlPath.getFileName().toString();
            resourceName = xmlName.substring(0, xmlName.length() - 4);
        }
        WzImage copy = new WzImage(resourceName, (WzObject)null, new BinaryReader(targetKey.getIv(), targetKey.getUserKey()));
        for (WzImageProperty child : source.getChildren()) copy.addChild(child.deepClone(copy));
        canvasCount = 0;
        linkCount = 0;
        try {
            for (WzImageProperty child : copy.getChildren()) {
                child.setWzImage(copy);
                child.setChildrenWzImage(copy);
                materialize(source.getChild(child.getName()), child, new HashSet<>());
            }
            int compatibilityChanges = 0;
            if (ToolParamHelper.getBoolean(params, "legacyCompatible", false)) {
                if (copy.getChild("info") instanceof WzListProperty info && info.getChild("icon") == null
                        && info.getChild("iconRaw") instanceof WzCanvasProperty raw) {
                    WzCanvasProperty icon = raw.deepClone(info);
                    icon.setName("icon");
                    icon.setWzImage(copy);
                    icon.setChildrenWzImage(copy);
                    info.addChild(icon);
                    compatibilityChanges++;
                }
                for (WzImageProperty child : copy.getChildren()) compatibilityChanges += normalizeLayerCase(child);
            }
            copy.setChanged(true);
            String expected = fingerprint(copy);
            BinaryWriter writer = new BinaryWriter();
            writer.setWzMutableKey(new WzMutableKey(targetKey.getIv(), targetKey.getUserKey()));
            copy.save(writer);
            byte[] bytes = writer.output();
            Files.createDirectories(output.getParent());
            Files.write(output, bytes, StandardOpenOption.CREATE_NEW);
            WzImage readback = new WzImage(output.getFileName().toString(), new BinaryReader(Files.readAllBytes(output), targetKey.getIv(), targetKey.getUserKey()), null);
            if (!readback.parse() || !expected.equals(fingerprint(readback))) throw new McpException("Read-back mismatch: " + output);
            readback.clear();
            if (xmlPath != null && !copy.exportToXml(xmlPath, 2, MediaExportType.NONE, true)) throw new McpException("XML export failed: " + xmlPath);
            return Map.of("filePath", output.toString(), "xmlPath", xml,
                    "sha256", hex(MessageDigest.getInstance("SHA-256").digest(bytes)),
                    "semanticSha256", expected, "canvasCount", canvasCount,
                    "resolvedLinks", linkCount, "compatibilityChanges", compatibilityChanges, "verified", true);
        } catch (Exception e) {
            throw new McpException("Portable export failed: " + e.getMessage());
        }
    }

    private int normalizeLayerCase(WzImageProperty node) {
        int changed = 0;
        if (node instanceof WzStringProperty text && text.getName().equals("z") && text.getValue().equals("backweapon")) {
            text.setValue("backWeapon");
            changed++;
        }
        if (node.isListProperty()) for (WzImageProperty child : node.getChildren()) changed += normalizeLayerCase(child);
        return changed;
    }

    private void materialize(WzImageProperty source, WzImageProperty copy, Set<WzObject> visiting) {
        if (source instanceof WzCanvasProperty canvas && copy instanceof WzCanvasProperty target) {
            WzCanvasProperty pixels = resolveCanvas(canvas, visiting);
            BufferedImage image = pixels.getPngImage(false);
            target.setPng(image, WzPngFormat.ARGB8888, 0);
            if (!pixelHash(image).equals(pixelHash(target.getPngImage(false)))) throw new McpException("Pixel conversion mismatch");
            for (String link : List.of("_outlink", "_inlink", "source")) {
                if (canvas.getChild(link) instanceof WzStringProperty) target.removeChild(link);
            }
            canvasCount++;
        } else if (source instanceof WzUOLProperty uol) {
            WzObject target = resolveRelative(uol.getParent(), uol.getValue());
            if (target == null) throw new McpException("Broken UOL: " + uol.getPath());
        }
        if (copy.isListProperty()) {
            for (WzImageProperty child : copy.getChildren()) materialize(source.getChild(child.getName()), child, visiting);
        }
    }

    private WzCanvasProperty resolveCanvas(WzCanvasProperty canvas, Set<WzObject> visiting) {
        if (!visiting.add(canvas)) throw new McpException("Canvas link cycle: " + canvas.getPath());
        try {
            WzObject linked;
            if (canvas.getChild("_outlink") instanceof WzStringProperty p) linked = resolveExternal(p.getValue());
            else if (canvas.getChild("_inlink") instanceof WzStringProperty p) linked = resolveRelative(canvas.getWzImage(), p.getValue());
            else if (canvas.getChild("source") instanceof WzStringProperty p) linked = resolveExternal(p.getValue());
            else return canvas;
            while (linked instanceof WzUOLProperty p) {
                if (!visiting.add(linked)) throw new McpException("UOL link cycle");
                linked = resolveRelative(p.getParent(), p.getValue());
            }
            if (!(linked instanceof WzCanvasProperty target)) throw new McpException("Canvas link target missing: " + canvas.getPath());
            linkCount++;
            return resolveCanvas(target, visiting);
        } finally { visiting.remove(canvas); }
    }

    private WzObject resolveExternal(String link) {
        String path = link.replace('\\', '/');
        int marker = path.indexOf(".img/");
        if (marker < 0) throw new McpException("Unsupported external link: " + link);
        int slash = path.lastIndexOf('/', marker);
        String dir = path.substring(0, slash);
        String imageName = path.substring(slash + 1, marker + 4);
        Path folder = dataRoot.resolve(dir).normalize();
        if (!folder.startsWith(dataRoot)) throw new McpException("Link escapes data root");
        String cacheKey = folder + "|" + sourceKey.getIvBase64() + "|" + sourceKey.getUserKeyBase64();
        Map<String, WzImage> images = linkIndexes.computeIfAbsent(cacheKey, k -> index(folder));
        WzImage image = images.get(imageName);
        if (image == null || !image.parse()) throw new McpException("External image missing: " + link);
        return resolveRelative(image, path.substring(marker + 5));
    }

    private Map<String, WzImage> index(Path folder) {
        Map<String, WzImage> result = new HashMap<>();
        try (var files = Files.list(folder)) {
            for (Path path : files.filter(p -> p.toString().endsWith(".wz")).sorted().toList()) {
                WzFile file = new WzFile(path.toString(), (short)-1, sourceKey.getName(), sourceKey.getIv(), sourceKey.getUserKey());
                if (!file.parse()) throw new McpException("Cannot parse linked WZ: " + path);
                for (WzObject child : file.getWzDirectory().getChildren()) {
                    if (child instanceof WzImage image && result.putIfAbsent(image.getName(), image) != null) throw new McpException("Duplicate external IMG: " + image.getName());
                }
            }
        } catch (java.io.IOException e) { throw new McpException("Cannot index links: " + folder); }
        return result;
    }

    private WzObject resolveRelative(WzObject base, String path) {
        WzObject current = base;
        for (String part : path.replace('\\', '/').split("/")) {
            if (part.isBlank() || part.equals(".")) continue;
            if (part.equals("..")) current = current == null ? null : current.getParent();
            else if (current instanceof WzImage img) current = img.getChild(part);
            else if (current instanceof WzImageProperty prop) current = prop.getChild(part);
            else return null;
        }
        return current;
    }

    static String fingerprint(WzImage image) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (WzImageProperty child : image.getChildren()) fingerprintNode(child, "", digest);
            return hex(digest.digest());
        } catch (Exception e) { throw new McpException("Fingerprint failed: " + e.getMessage()); }
    }

    private static void fingerprintNode(WzImageProperty p, String prefix, MessageDigest d) {
        String path = prefix + "/" + p.getName();
        String value = switch (p) {
            case WzIntProperty x -> String.valueOf(x.getValue());
            case WzShortProperty x -> String.valueOf(x.getValue());
            case WzLongProperty x -> String.valueOf(x.getValue());
            case WzFloatProperty x -> String.valueOf(x.getValue());
            case WzDoubleProperty x -> String.valueOf(x.getValue());
            case WzStringProperty x -> x.getValue();
            case WzUOLProperty x -> x.getValue();
            case WzVectorProperty x -> x.getX() + "," + x.getY();
            case WzCanvasProperty x -> pixelHash(x.getPngImage(false));
            case WzSoundProperty x -> Base64.getEncoder().encodeToString(x.getSoundBytes());
            case WzListProperty x -> "";
            case WzConvexProperty x -> "";
            case WzNullProperty x -> "";
            default -> throw new McpException("Unsupported verification type: " + p.getType());
        };
        byte[] bytes = (path + "\u0000" + p.getType() + "\u0000" + value).getBytes(StandardCharsets.UTF_8);
        d.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
        d.update(bytes);
        if (p.isListProperty()) for (WzImageProperty child : p.getChildren()) fingerprintNode(child, path, d);
    }

    private static String pixelHash(BufferedImage image) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            d.update(ByteBuffer.allocate(8).putInt(image.getWidth()).putInt(image.getHeight()).array());
            for (int y=0; y<image.getHeight(); y++) {
                ByteBuffer row = ByteBuffer.allocate(image.getWidth()*4);
                for (int x=0; x<image.getWidth(); x++) row.putInt(image.getRGB(x,y));
                d.update(row.array());
            }
            return hex(d.digest());
        } catch (Exception e) { throw new McpException("Pixel hash failed: " + e.getMessage()); }
    }

    private static String hex(byte[] bytes) { return HexFormat.of().formatHex(bytes); }
}
