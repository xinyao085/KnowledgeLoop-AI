package com.example.server.service;

import com.example.server.dto.*;
import com.example.server.utils.MinioUtils;
import com.fasterxml.jackson.databind.*;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipInputStream;

/** MinerU cloud v4 batch upload contract; credentials never sent to result/upload hosts. */
@Service
public class MineruDocumentParser {
    private final ObjectMapper json;
    private final MinioUtils minio;
    private final String token, base;
    private final OkHttpClient http = new OkHttpClient.Builder().callTimeout(180, TimeUnit.SECONDS).build();
    public MineruDocumentParser(ObjectMapper json, MinioUtils minio,
            @Value("${mineru.api-token:}") String token,
            @Value("${mineru.base-url:https://mineru.net}") String base) {
        this.json=json; this.minio=minio; this.token=token; this.base=base.replaceAll("/+$", "");
    }
    private JsonNode api(String path, Object body) throws IOException {
        Request.Builder request = new Request.Builder().url(base + path).header("Authorization", "Bearer " + token);
        if (body != null) request.post(RequestBody.create(json.writeValueAsBytes(body), MediaType.get("application/json")));
        try (Response response = http.newCall(request.build()).execute()) {
            if (!response.isSuccessful() || response.body()==null) throw new IOException("MinerU HTTP " + response.code());
            JsonNode result = json.readTree(response.body().byteStream());
            if (result.path("code").asInt(-1)!=0) throw new IOException("MinerU request failed");
            return result.path("data");
        }
    }
    public DocumentContext parse(Long id, String source, String filename, Path file) throws Exception {
        if (Files.size(file) > 200L*1024*1024) throw new IllegalArgumentException("MinerU maximum file size is 200 MiB");
        if (token.isBlank()) throw new IllegalStateException("MINERU_API_TOKEN is required");
        JsonNode upload = api("/api/v4/file-urls/batch", Map.of("files", List.of(Map.of("name",filename,"data_id","doc-"+id)),
                "model_version","vlm", "enable_table",true,"enable_formula",true));
        String batch = upload.path("batch_id").asText();
        String url = upload.path("file_urls").path(0).asText();
        if (batch.isBlank() || url.isBlank()) throw new IOException("Invalid MinerU upload response");
        try (Response response = http.newCall(new Request.Builder().url(url)
                .put(RequestBody.create(file.toFile(), MediaType.get("application/octet-stream"))).build()).execute()) {
            if (!response.isSuccessful()) throw new IOException("MinerU upload failed: " + response.code());
        }
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(20);
        while (System.nanoTime() < deadline) {
            JsonNode results = api("/api/v4/extract-results/batch/" + batch, null).path("extract_result");
            for (JsonNode result : results) {
                String state = result.path("state").asText();
                if (state.equals("failed")) throw new IOException("MinerU extraction failed");
                if (state.equals("done")) return readArchive(id, source, filename, result.path("full_zip_url").asText());
            }
            Thread.sleep(2000);
        }
        throw new IOException("MinerU parsing exceeded 20 minutes");
    }
    private DocumentContext readArchive(Long id, String source, String filename, String url) throws Exception {
        Map<String, byte[]> entries = new HashMap<>();
        long total=0;
        try (Response response=http.newCall(new Request.Builder().url(url).build()).execute()) {
            if (!response.isSuccessful() || response.body()==null) throw new IOException("MinerU archive unavailable");
            try (ZipInputStream zip=new ZipInputStream(response.body().byteStream())) {
                for (var entry=zip.getNextEntry();entry!=null;entry=zip.getNextEntry()) {
                    if (entry.isDirectory()) continue;
                    String name=entry.getName().replace('\\','/');
                    if (name.startsWith("/") || Arrays.asList(name.split("/")).contains("..")) throw new IOException("Unsafe archive path");
                    byte[] bytes=zip.readNBytes(32*1024*1024+1);
                    total+=bytes.length;
                    if (bytes.length>32*1024*1024 || total>256L*1024*1024 || entries.size()>=10000) throw new IOException("MinerU archive too large");
                    entries.put(name,bytes);
                }
            }
        }
        String contentName=entries.keySet().stream().filter(n->n.endsWith("_content_list.json") || n.equals("content_list.json"))
                .sorted().findFirst().orElseThrow(()->new IOException("MinerU content list missing"));
        JsonNode blocks=json.readTree(entries.get(contentName));
        if (!blocks.isArray()) throw new IOException("Unsupported MinerU content-list schema");
        List<DocumentSegment> segments=new ArrayList<>();
        String heading="";
        for (JsonNode block:blocks) {
            String type=block.path("type").asText();
            String text=block.path("text").asText("");
            if (type.equals("table")) text=block.path("table_body").asText("");
            List<String> images=new ArrayList<>();
            if (type.equals("image")) {
                String imagePath=block.path("img_path").asText();
                String parent=contentName.contains("/")?contentName.substring(0,contentName.lastIndexOf('/')+1):"";
                byte[] bytes=entries.getOrDefault(parent+imagePath,entries.get(imagePath));
                if (bytes != null) {
                    var image = javax.imageio.ImageIO.read(new ByteArrayInputStream(bytes));
                    if (image == null) throw new IOException("Unsupported extracted image format");
                    ByteArrayOutputStream png = new ByteArrayOutputStream();
                    javax.imageio.ImageIO.write(image, "png", png);
                    byte[] normalized = png.toByteArray();
                    images.add(minio.uploadObject("documents/"+id+"/images/"+UUID.randomUUID()+".png",
                            new ByteArrayInputStream(normalized),normalized.length,"image/png"));
                }
                text=join(block.path("image_caption"));
            }
            if (type.equals("table")) text=join(block.path("table_caption"))+"\n"+text;
            if (block.path("text_level").asInt(0)>0) heading=text;
            if (text.isBlank() && images.isEmpty()) continue;
            Integer page=block.has("page_idx")?block.path("page_idx").asInt()+1:null;
            boolean ppt=filename.toLowerCase(Locale.ROOT).endsWith(".pptx");
            segments.add(new DocumentSegment("mineru-"+segments.size(),heading,ppt?null:page,ppt?page:null,
                    null,null,null,text,List.copyOf(images),List.of()));
        }
        if (segments.isEmpty()) throw new IOException("MinerU returned no content");
        return new DocumentContext(id,source,filename,DocumentType.fromFilename(filename),List.copyOf(segments));
    }
    private String join(JsonNode node) {
        if (!node.isArray()) return node.asText("");
        List<String> parts=new ArrayList<>(); node.forEach(n->parts.add(n.asText())); return String.join("\n",parts);
    }
}
