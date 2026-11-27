package com.threecats.tripservice.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OperationResponse {
    private UUID id;
    private UUID trip_id;
    private String type;
    private String status;
    private String error_code;
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String error_message;
    private String created_at;
    private String updated_at;
}
