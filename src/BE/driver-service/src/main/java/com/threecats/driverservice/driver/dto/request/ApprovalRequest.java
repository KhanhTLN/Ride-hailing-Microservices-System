package com.threecats.driverservice.driver.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApprovalRequest {

    @NotBlank(message = "Account status is required")
    @JsonProperty("account_status")
    private String accountStatus; // APPROVED or SUSPENDED

    @NotBlank(message = "Reason is required")
    @Size(min = 1, max = 300, message = "Reason must be between 1 and 300 characters")
    private String reason;
}
