package gr.aegean.cinema.service;

import gr.aegean.cinema.dto.screening.*;

import java.time.LocalDateTime;
import java.util.List;

public interface ScreeningService {

    ScreeningResponse createScreening(Long programId, ScreeningCreateRequest request);

    ScreeningResponse updateScreening(Long screeningId, ScreeningUpdateRequest request);

    ScreeningResponse submitScreening(Long screeningId);

    void withdrawScreening(Long screeningId);

    ScreeningResponse assignHandler(Long screeningId, HandlerAssignRequest request);

    ScreeningResponse reviewScreening(Long screeningId, ReviewRequest request);

    ScreeningResponse approveScreening(Long screeningId, ApprovalRequest request);

    ScreeningResponse rejectScreening(Long screeningId, RejectionRequest request);

    ScreeningResponse finalSubmitScreening(Long screeningId, ScreeningUpdateRequest request);

    ScreeningResponse acceptScreening(Long screeningId);

    List<ScreeningResponse> searchScreenings(Long programId, String title, String cast, String genre,
                                              LocalDateTime dateFrom, LocalDateTime dateTo, boolean timetable);

    ScreeningResponse viewScreening(Long screeningId);
}
