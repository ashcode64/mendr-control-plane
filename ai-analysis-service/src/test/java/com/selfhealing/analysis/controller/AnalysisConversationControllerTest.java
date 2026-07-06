package com.selfhealing.analysis.controller;

import com.selfhealing.analysis.dto.AnalysisConversationDto;
import com.selfhealing.analysis.dto.AppendConversationMessagesRequest;
import com.selfhealing.analysis.service.AnalysisConversationService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisConversationControllerTest {

    private final AnalysisConversationService service = Mockito.mock(AnalysisConversationService.class);
    private final AnalysisConversationController controller = new AnalysisConversationController(service);

    @Test
    void getConversationReturnsConversationDto() {
        UUID id = UUID.randomUUID();
        AnalysisConversationDto dto = AnalysisConversationDto.builder()
                .analysisId(id)
                .sessionId("session-1")
                .chatEnabled(true)
                .messages(List.of())
                .build();
        Mockito.when(service.getOrCreateConversation(id, 20)).thenReturn(dto);

        var response = controller.getConversation(id, 20);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(dto);
    }

    @Test
    void appendMessagesReturnsConflictWhenChatIsReadOnly() {
        UUID id = UUID.randomUUID();
        Mockito.when(service.appendMessages(Mockito.eq(id), Mockito.any()))
                .thenThrow(new IllegalStateException("chat is read-only after approval or rejection"));

        var response = controller.appendMessages(id, new AppendConversationMessagesRequest(
                List.of(), Map.of("status", "ready")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isEqualTo(Map.of("error", "chat is read-only after approval or rejection"));
    }

    @Test
    void getConversationReturns404WhenAnalysisMissing() {
        UUID id = UUID.randomUUID();
        Mockito.when(service.getOrCreateConversation(id, 20)).thenThrow(new NoSuchElementException("analysis not found"));

        var response = controller.getConversation(id, 20);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
