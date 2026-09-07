package com.betterself.growth.town;
import com.betterself.growth.auth.CurrentUser;
import com.betterself.growth.shared.api.ApiEnvelope;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.time.Clock;
import java.util.List;
@RestController
@RequestMapping("/api/v1/town/visits")
public class TownVisitController {
    private final TownVisitService visits; private final Clock clock;
    public TownVisitController(TownVisitService visits, Clock clock) { this.visits=visits;this.clock=clock; }
    @GetMapping("/mine") ApiEnvelope<TownVisitService.Profile> mine(@AuthenticationPrincipal CurrentUser u,HttpServletRequest r) { return wrap(visits.mine(u.id()),r); }
    @PutMapping("/mine") ApiEnvelope<TownVisitService.Profile> save(@AuthenticationPrincipal CurrentUser u,@RequestBody TownVisitService.SaveProfile body,HttpServletRequest r) { return wrap(visits.save(u.id(),body),r); }
    @GetMapping ApiEnvelope<List<TownVisitService.DirectoryEntry>> directory(@AuthenticationPrincipal CurrentUser u,HttpServletRequest r) { return wrap(visits.directory(u.id()),r); }
    @GetMapping("/received") ApiEnvelope<List<TownVisitService.Postcard>> received(@AuthenticationPrincipal CurrentUser u,HttpServletRequest r) { return wrap(visits.received(u.id()),r); }
    @GetMapping("/{owner}") ApiEnvelope<TownVisitService.Profile> visit(@AuthenticationPrincipal CurrentUser u,@PathVariable String owner,HttpServletRequest r) { return wrap(visits.visit(u.id(),owner),r); }
    @PostMapping("/{owner}/postcards") ApiEnvelope<TownVisitService.Postcard> send(@AuthenticationPrincipal CurrentUser u,@PathVariable String owner,@RequestBody TownVisitService.SendPostcard body,HttpServletRequest r) { return wrap(visits.send(u.id(),owner,body),r); }
    @DeleteMapping("/postcards/{id}") ApiEnvelope<Void> remove(@AuthenticationPrincipal CurrentUser u,@PathVariable String id,HttpServletRequest r) { visits.remove(u.id(),id);return wrap(null,r); }
    private <T> ApiEnvelope<T> wrap(T data,HttpServletRequest r) { return ApiEnvelope.of(data,String.valueOf(r.getAttribute("requestId")),clock); }
}
