package com.example.server.mapper;
import org.apache.ibatis.annotations.*;
import java.util.Map;

@Mapper
public interface UploadSessionMapper {
 @Insert("INSERT INTO upload_sessions(upload_id,user_id,filename,mime_type,total_chunks,total_size,chunk_size,fingerprint) VALUES(#{id},#{userId},#{filename},#{mime},#{total},#{size},#{chunkSize},#{fingerprint})")
 void create(@Param("id")String id,@Param("userId")Long userId,@Param("filename")String filename,@Param("mime")String mime,@Param("total")int total,@Param("size")long size,@Param("chunkSize")int chunkSize,@Param("fingerprint")String fingerprint);
 @Select("SELECT upload_id uploadId,user_id userId,filename,mime_type mimeType,total_chunks totalChunks,received_chunks receivedChunks,status,total_size totalSize,chunk_size chunkSize,fingerprint,document_id documentId FROM upload_sessions WHERE upload_id=#{id}")
 Map<String,Object> find(@Param("id")String id);
 @Update("UPDATE upload_sessions SET received_chunks=#{received},updated_at=CURRENT_TIMESTAMP(3) WHERE upload_id=#{id}") void progress(@Param("id")String id,@Param("received")int received);
 @Update("UPDATE upload_sessions SET status=#{status},updated_at=CURRENT_TIMESTAMP(3) WHERE upload_id=#{id}") void status(@Param("id")String id,@Param("status")String status);
 @Update("UPDATE upload_sessions SET status='COMPLETED',document_id=#{documentId},updated_at=CURRENT_TIMESTAMP(3) WHERE upload_id=#{id}")
 void complete(@Param("id")String id,@Param("documentId")Long documentId);
}
