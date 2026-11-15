package com.nasmount.mobile;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.xml.sax.SAXException;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Offline DRM-free EPUB2/EPUB3 OCF reader. No full-book extraction. */
final class EpubArchive implements Closeable {
    static final class Chapter {
        final String path;
        Chapter(String path){this.path=path;}
    }
    final List<Chapter> chapters=new ArrayList<>();
    final String title;
    final boolean rtl;
    private final ZipFile zip;

    EpubArchive(File file)throws Exception {
        // No fixed total book-size limit: individual chapter/resource reads
        // retain bounded decompression checks against archive bombs.
        if(!file.isFile()||file.length()==0)
            throw new IOException("EPUB 파일이 없거나 비어 있습니다.");
        zip=new ZipFile(file);
        try{
            Document root=parseXml(read("META-INF/container.xml",1024*1024));
            NodeList files=root.getElementsByTagNameNS("*","rootfile");
            if(files.getLength()==0)throw new IOException("EPUB container.xml이 올바르지 않습니다.");
            String packagePath=((Element)files.item(0)).getAttribute("full-path");
            packagePath=normalized("",packagePath);
            Document packageXml=parseXml(read(packagePath,2*1024*1024));
            NodeList titles=packageXml.getElementsByTagNameNS("*","title");
            title=titles.getLength()>0?titles.item(0).getTextContent().trim():file.getName();
            NodeList spineNodes=packageXml.getElementsByTagNameNS("*","spine");
            if(spineNodes.getLength()==0)throw new IOException("EPUB 목차(spine)가 없습니다.");
            Element spine=(Element)spineNodes.item(0);
            rtl="rtl".equalsIgnoreCase(spine.getAttribute("page-progression-direction"));
            Map<String,String> itemById=new HashMap<>();
            NodeList manifest=packageXml.getElementsByTagNameNS("*","item");
            for(int i=0;i<manifest.getLength();i++){
                Element item=(Element)manifest.item(i);
                String type=item.getAttribute("media-type");
                if(type.equals("application/xhtml+xml")||type.equals("text/html")){
                    String path=normalized(packagePath,item.getAttribute("href"));
                    itemById.put(item.getAttribute("id"),path);
                }
            }
            NodeList order=spine.getElementsByTagNameNS("*","itemref");
            for(int i=0;i<order.getLength()&&chapters.size()<2000;i++){
                Element ref=(Element)order.item(i);
                if("no".equalsIgnoreCase(ref.getAttribute("linear")))continue;
                String path=itemById.get(ref.getAttribute("idref"));
                if(path!=null&&zip.getEntry(path)!=null)
                    chapters.add(new Chapter(path));
            }
            if(chapters.isEmpty())throw new IOException("EPUB 본문을 찾지 못했습니다.");
        }catch(Exception invalid){
            zip.close();throw invalid;
        }
    }
    /**
     * Android's platform DocumentBuilderFactory does not implement several
     * Apache/Xerces SAX feature URIs. Those flags are optional defense-in-
     * depth, never a prerequisite for opening an ordinary EPUB.
     *
     * Reject all DTD and ENTITY declarations *before* invoking the parser,
     * including UTF-16 XML, and deny external entities at the resolver. This
     * keeps the primary XXE protection even if platform features are absent.
     */
    static Document parseXml(byte[] raw)throws Exception {
        rejectUnsafeXml(raw);
        DocumentBuilderFactory factory=DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setValidating(false);
        optionalFeature(factory,
                "http://apache.org/xml/features/disallow-doctype-decl",true);
        optionalFeature(factory,
                "http://xml.org/sax/features/external-general-entities",false);
        optionalFeature(factory,
                "http://xml.org/sax/features/external-parameter-entities",false);
        optionalFeature(factory,
                "http://apache.org/xml/features/nonvalidating/load-external-dtd",false);
        try { factory.setXIncludeAware(false); }
        catch(UnsupportedOperationException ignored) {
            // Android's parser may not expose XInclude options.
        }
        factory.setExpandEntityReferences(false);
        DocumentBuilder builder=factory.newDocumentBuilder();
        builder.setEntityResolver((publicId,systemId)->{
            throw new SAXException("외부 XML 엔티티는 허용하지 않습니다.");
        });
        return builder.parse(new java.io.ByteArrayInputStream(raw));
    }
    static boolean optionalFeature(DocumentBuilderFactory factory,
                                   String feature,boolean enabled) {
        try {
            factory.setFeature(feature,enabled);
            return true;
        }catch(ParserConfigurationException | IllegalArgumentException |
               UnsupportedOperationException ignored) {
            // Feature is unsupported by this Android XML implementation.
            return false;
        }
    }
    static void rejectUnsafeXml(byte[] raw)throws IOException {
        if(raw==null||raw.length==0)
            throw new IOException("EPUB XML 본문이 비어 있습니다.");
        // XML declaration/DOCTYPE markup is ASCII even when the surrounding
        // content is UTF-16/UTF-32. Strip interleaved zero bytes so the same
        // protection is effective for all common XML byte encodings.
        String markup=new String(raw,StandardCharsets.ISO_8859_1)
                .replace("\u0000","");
        if(java.util.regex.Pattern.compile(
                "(?i)<!\\s*(?:DOCTYPE|ENTITY)\\b").matcher(markup).find())
            throw new IOException("EPUB XML의 DTD/ENTITY 선언은 보안상 허용하지 않습니다.");
    }
    /** Normalize relative paths while rejecting traversal beyond EPUB root. */
    static String normalized(String parent,String href)throws IOException{
        if(href==null||href.isEmpty()||href.contains("://")||
                href.startsWith("/")||href.indexOf('\0')>=0)
            throw new IOException("허용되지 않은 EPUB 내부 경로");
        String clean=href.split("[?#]",2)[0];
        try{clean=URLDecoder.decode(clean.replace("+","%2B"),"UTF-8");}
        catch(Exception invalid){throw new IOException("잘못된 EPUB 경로",invalid);}
        String base=parent.contains("/")?parent.substring(0,parent.lastIndexOf('/')+1):"";
        List<String> parts=new ArrayList<>();
        for(String part:(base+clean).replace('\\','/').split("/")){
            if(part.isEmpty()||part.equals("."))continue;
            if(part.equals("..")){
                if(parts.isEmpty())throw new IOException("EPUB 경로 이탈");
                parts.remove(parts.size()-1);
            }else parts.add(part);
        }
        String result=String.join("/",parts);
        if(result.isEmpty()||result.length()>1024)
            throw new IOException("올바르지 않은 EPUB 경로");
        return result;
    }
    byte[] read(String path,long limit)throws IOException{
        ZipEntry entry=zip.getEntry(path);
        if(entry==null||entry.isDirectory())
            throw new IOException("EPUB 리소스를 찾지 못했습니다: "+path);
        if(entry.getSize()>limit)
            throw new IOException("EPUB 내부 파일 크기 제한 초과");
        try(InputStream input=zip.getInputStream(entry);
            ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buffer=new byte[32768];long count=0;int n;
            while((n=input.read(buffer))!=-1){
                count+=n;
                if(count>limit)throw new IOException("EPUB 압축 해제 크기 제한 초과");
                out.write(buffer,0,n);
            }
            return out.toByteArray();
        }
    }
    byte[] chapter(int index)throws IOException {
        return read(chapters.get(index).path,4*1024*1024);
    }
    /**
     * Build reading weights using actual chapter body text instead of the
     * number of spine entries. Keep work bounded on unusually huge EPUBs:
     * if XHTML entries exceed 64 MiB, fall back to fast uncompressed sizes.
     * The whole EPUB file size (images/fonts included) is irrelevant here.
     */
    long[] readingWeights(){
        long[] weights=new long[chapters.size()];
        long totalBytes=0;
        for(int i=0;i<chapters.size();i++){
            ZipEntry entry=zip.getEntry(chapters.get(i).path);
            long bytes=entry==null?0:Math.max(1,entry.getSize());
            weights[i]=Math.max(1,bytes);
            totalBytes+=bytes;
        }
        if(totalBytes>64L*1024*1024)return weights;
        for(int i=0;i<chapters.size();i++){
            if(Thread.currentThread().isInterrupted())break;
            try{
                String html=new String(chapter(i),StandardCharsets.UTF_8);
                weights[i]=Math.max(1,visibleTextLength(html));
            }catch(Exception unavailable){
                // A malformed or unusually large individual XHTML must not
                // prevent opening the entire book. Its ZIP entry size
                // remains a safe fallback estimate.
            }
        }
        return weights;
    }
    static int visibleTextLength(String html){
        if(html==null||html.isEmpty())return 0;
        String clean=html.replaceAll(
                "(?is)<(head|style|script|nav|svg)\\b[^>]*>.*?</\\1>"," ");
        clean=clean.replaceAll("(?s)<!--.*?-->"," ");
        clean=clean.replaceAll("(?s)<[^>]*>"," ");
        clean=clean.replaceAll("(?i)&(?:#[0-9]+|#x[0-9a-f]+|[a-z]+);"," ");
        return clean.replaceAll("\\s+"," ").trim().length();
    }
    byte[] resource(String absolutePath)throws IOException {
        if(absolutePath==null||absolutePath.isEmpty()||absolutePath.startsWith("/"))
            throw new IOException("허용되지 않은 리소스");
        String clean=normalized("",absolutePath);
        return read(clean,24*1024*1024);
    }
    static String mime(String path){
        String n=path.toLowerCase(Locale.ROOT);
        if(n.endsWith(".png"))return "image/png";
        if(n.endsWith(".jpg")||n.endsWith(".jpeg"))return "image/jpeg";
        if(n.endsWith(".gif"))return "image/gif";
        if(n.endsWith(".webp"))return "image/webp";
        if(n.endsWith(".svg"))return "image/svg+xml";
        if(n.endsWith(".css"))return "text/css";
        if(n.endsWith(".otf"))return "font/otf";
        if(n.endsWith(".ttf"))return "font/ttf";
        return "application/octet-stream";
    }
    @Override public void close()throws IOException{zip.close();}
}
