package com.dataintegration.console.server.message;

import com.dataintegration.console.common.PageResult;
import com.dataintegration.console.common.Result;
import com.dataintegration.console.server.message.MessageService.Filter;
import com.dataintegration.console.server.message.MessageService.MessageView;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/messages")
public class MessageController {

    private final MessageService messageService;

    public MessageController(MessageService messageService) {
        this.messageService = messageService;
    }

    @GetMapping
    public Result<PageResult<MessageView>> list(
            @RequestParam(defaultValue = "false") boolean unread,
            @RequestParam(defaultValue = "false") boolean unresolved,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return Result.ok(messageService.list(new Filter(unread, unresolved, blankToNull(level), blankToNull(category)), page, size));
    }

    @GetMapping("/unread-count")
    public Result<Long> unreadCount() {
        return Result.ok(messageService.unreadCount());
    }

    @PostMapping("/{id}/read")
    public Result<Void> markRead(@PathVariable String id) {
        messageService.markRead(id);
        return Result.ok();
    }

    @PostMapping("/read-all")
    public Result<Void> markAllRead() {
        messageService.markAllRead();
        return Result.ok();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
