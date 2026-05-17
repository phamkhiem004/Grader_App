package com.example.grader.dto;


import lombok.*;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class ExamSetupResponse {
    String examId;
    String imageName;
    String status;


}
