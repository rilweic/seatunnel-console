package com.dataintegration.console.server.encryptkey;

import com.dataintegration.console.common.Result;
import com.dataintegration.console.server.encryptkey.EncryptKeyService.KeyView;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/encrypt-keys")
public class EncryptKeyController {

    /** @param importedKey a Base64 key to import; blank generates one of {@code bits} */
    public record CreateRequest(
            @NotBlank @Size(max = 64) String name,
            @Size(max = 512) String description,
            String algorithm,
            Integer bits,
            String importedKey) {}

    public record UpdateRequest(@NotBlank @Size(max = 64) String name, @Size(max = 512) String description, boolean enabled) {}

    private final EncryptKeyService service;

    public EncryptKeyController(EncryptKeyService service) {
        this.service = service;
    }

    @GetMapping
    public Result<List<KeyView>> list() {
        return Result.ok(service.list());
    }

    @PostMapping
    public Result<KeyView> create(@Valid @RequestBody CreateRequest body) {
        return Result.ok(service.create(body.name(), body.description(), body.algorithm(), body.bits(), body.importedKey()));
    }

    @PutMapping("/{id}")
    public Result<KeyView> update(@PathVariable String id, @Valid @RequestBody UpdateRequest body) {
        return Result.ok(service.update(id, body.name(), body.description(), body.enabled()));
    }
}
