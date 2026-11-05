package com.example.server.service;

import com.example.server.utils.MinioUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.util.zip.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class MineruDocumentParserTest {
    @TempDir Path directory;
    @Test void cloudUploadPollAndArchivePreserveTablesFormulasAndPageLocations() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        String base="http://127.0.0.1:"+server.getAddress().getPort();
        byte[] archive=zip("lesson_content_list.json", """
                [{"type":"text","text":"章节一","text_level":1,"page_idx":0},
                 {"type":"table","table_body":"<table><tr><td>42</td></tr></table>","page_idx":1},
                 {"type":"equation","text":"E=mc^2","page_idx":2}]
                """);
        server.createContext("/api/v4/file-urls/batch", e->{
            byte[] body=("{\"code\":0,\"data\":{\"batch_id\":\"batch-1\",\"file_urls\":[\""+base+"/upload\"]}}").getBytes();
            e.sendResponseHeaders(200,body.length);e.getResponseBody().write(body);e.close();
        });
        server.createContext("/upload", e->{e.getRequestBody().readAllBytes();e.sendResponseHeaders(200,-1);e.close();});
        server.createContext("/api/v4/extract-results/batch/batch-1", e->{
            byte[] body=("{\"code\":0,\"data\":{\"extract_result\":[{\"state\":\"done\",\"full_zip_url\":\""+base+"/archive\"}]}}").getBytes();
            e.sendResponseHeaders(200,body.length);e.getResponseBody().write(body);e.close();
        });
        server.createContext("/archive",e->{e.sendResponseHeaders(200,archive.length);e.getResponseBody().write(archive);e.close();});
        server.start();
        try {
            Path file=directory.resolve("lesson.pdf");Files.writeString(file,"test fixture");
            var parser=new MineruDocumentParser(new ObjectMapper(),mock(MinioUtils.class),"test-token",base);
            var context=parser.parse(1L,"source","lesson.pdf",file);
            assertThat(context.segments()).hasSize(3);
            assertThat(context.segments().get(1).text()).contains("<table>");
            assertThat(context.segments().get(1).pageNumber()).isEqualTo(2);
            assertThat(context.segments().get(2).text()).isEqualTo("E=mc^2");
        } finally {server.stop(0);}
    }
    private byte[] zip(String name,String text) throws IOException {
        var buffer=new ByteArrayOutputStream();
        try(var zip=new ZipOutputStream(buffer)){zip.putNextEntry(new ZipEntry(name));zip.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));zip.closeEntry();}
        return buffer.toByteArray();
    }
}
