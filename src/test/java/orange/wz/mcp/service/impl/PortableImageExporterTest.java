package orange.wz.mcp.service.impl;

import orange.wz.mcp.service.McpWorkspaceService;
import orange.wz.mcp.session.McpSessionState;
import orange.wz.mcp.session.McpSessionManager;
import orange.wz.mcp.support.McpException;
import orange.wz.provider.*;
import orange.wz.provider.properties.*;
import orange.wz.provider.tools.BinaryReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import static orange.wz.provider.WzAESConstant.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PortableImageExporterTest {
    @TempDir Path dir;

    private Map<String,Object> key(byte[] iv) {
        return Map.of("name","test","ivBase64",Base64.getEncoder().encodeToString(iv),
                "userKeyBase64",Base64.getEncoder().encodeToString(DEFAULT_KEY));
    }
    private Map<String,Object> params() {
        return Map.of("rootPath","source","nodePath","sample.img","filePath",dir.resolve("out.img").toString(),
                "xmlPath",dir.resolve("out.xml").toString(),"sourceDataRoot",dir.toString(),
                "sourceKey",key(WZ_EMPTY_IV),"targetKey",key(WZ_GMS_IV));
    }
    private WzImage image() {
        WzImage image = new WzImage("sample.img",(WzObject)null,new BinaryReader(WZ_EMPTY_IV,DEFAULT_KEY));
        WzCanvasProperty real = new WzCanvasProperty("real",image,image);
        real.initPngProperty("real",real,image);
        BufferedImage pixels = new BufferedImage(2,1,BufferedImage.TYPE_INT_ARGB);
        pixels.setRGB(0,0,0x89abcdef); pixels.setRGB(1,0,0xff321098);
        real.setPng(pixels,WzPngFormat.ARGB8888,0);
        image.addChild(real);
        WzCanvasProperty alias = new WzCanvasProperty("alias",image,image);
        alias.initPngProperty("alias",alias,image);
        alias.setPng(new BufferedImage(1,1,BufferedImage.TYPE_INT_ARGB),WzPngFormat.ARGB4444,0);
        alias.addChild(new WzStringProperty("_inlink","real",alias,image));
        alias.addChild(new WzVectorProperty("origin",7,9,alias,image));
        image.addChild(alias);
        return image;
    }
    private PortableImageExporter exporter(WzImage image) {
        McpWorkspaceService service=mock(McpWorkspaceService.class);
        when(service.findNode(any(),any(),eq(true))).thenReturn(image);
        return new PortableImageExporter(service);
    }
    @Test void resolvesPixelsReencryptsAndPreservesOriginal() throws Exception {
        WzImage source=image();String before=PortableImageExporter.fingerprint(source);
        var result=exporter(source).export(new McpSessionManager().createSession(),params());
        assertEquals(true,result.get("verified"));assertEquals(1,result.get("resolvedLinks"));
        WzImage actual=new WzImage("out.img",new BinaryReader(Files.readAllBytes(dir.resolve("out.img")),WZ_GMS_IV,DEFAULT_KEY),null);
        assertTrue(actual.parse());
        WzCanvasProperty alias=(WzCanvasProperty)actual.getChild("alias");
        assertNull(alias.getChild("_inlink"));assertEquals(2,alias.getWidth());
        assertEquals(0x89abcdef,alias.getPngImage(false).getRGB(0,0));
        assertEquals(7,((WzVectorProperty)alias.getChild("origin")).getX());
        assertEquals(before,PortableImageExporter.fingerprint(source));
        assertFalse(Files.readString(dir.resolve("out.xml")).contains("basedata"));
    }
    @Test void rejectsCyclesBeforeWriting() {
        WzImage source=image();
        ((WzStringProperty)((WzCanvasProperty)source.getChild("alias")).getChild("_inlink")).setValue("alias");
        assertThrows(McpException.class,()->exporter(source).export(new McpSessionManager().createSession(),params()));
        assertFalse(Files.exists(dir.resolve("out.img")));
    }

    @Test void preservesTileConvexCoordinatesAcrossExport() throws Exception {
        WzImage source = image();
        WzConvexProperty convex = new WzConvexProperty("foothold", source, source);
        convex.addChild(new WzVectorProperty("foothold", -15, 27, convex, source));
        source.addChild(convex);
        var result = exporter(source).export(new McpSessionManager().createSession(), params());
        assertEquals(true, result.get("verified"));
        WzImage actual = new WzImage("out.img", new BinaryReader(
                Files.readAllBytes(dir.resolve("out.img")), WZ_GMS_IV, DEFAULT_KEY), null);
        assertTrue(actual.parse());
        WzConvexProperty found = (WzConvexProperty) actual.getChild("foothold");
        WzVectorProperty point = (WzVectorProperty) found.getChildren().getFirst();
        assertEquals(-15, point.getX());
        assertEquals(27, point.getY());
        String before = PortableImageExporter.fingerprint(actual);
        point.setX(-16);
        assertNotEquals(before, PortableImageExporter.fingerprint(actual));
    }
    @Test void refusesOverwrite() throws Exception {
        Files.writeString(dir.resolve("out.img"),"keep");
        assertThrows(McpException.class,()->exporter(image()).export(new McpSessionManager().createSession(),params()));
        assertEquals("keep",Files.readString(dir.resolve("out.img")));
    }

    @Test void xmlRootUsesResourceBasenameInsteadOfWorkingPath() throws Exception {
        WzImage source = image();
        String workingName = "E:\\client\\_codex_backup\\working\\zmap.img";
        source.setName(workingName);
        Map<String,Object> p = new HashMap<>(params());
        Path xml = dir.resolve("zmap.img.xml");
        p.put("xmlPath", xml.toString());
        exporter(source).export(new McpSessionManager().createSession(), p);
        var document = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.toFile());
        assertEquals("zmap.img", document.getDocumentElement().getAttribute("name"));
        assertEquals(workingName, source.getName());
    }

    @Test void xmlRootDoesNotInheritXmlExtension() throws Exception {
        WzImage source = image();
        source.setName("Eqp.img.xml");
        Map<String,Object> p = new HashMap<>(params());
        Path xml = dir.resolve("Eqp.img.xml");
        p.put("xmlPath", xml.toString());
        exporter(source).export(new McpSessionManager().createSession(), p);
        var document = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.toFile());
        assertEquals("Eqp.img", document.getDocumentElement().getAttribute("name"));
        assertEquals("Eqp.img.xml", source.getName());
    }
}
