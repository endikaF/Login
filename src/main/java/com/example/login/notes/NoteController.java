package com.example.login.notes;

import java.util.List;
import java.util.Map;

import com.example.login.auth.AuthenticatedUser;
import com.example.login.notes.NoteRepository.Note;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Recurso privado por usuario; solo existe para poder probar la autorización por propiedad.
 * El propietario NUNCA viene en la petición: se toma del usuario autenticado.
 */
@RestController
@RequestMapping("/api/notes")
@PreAuthorize("hasAnyRole('USER','ADMIN')")
public class NoteController {

    public record NoteRequest(@NotBlank @Size(max = 100) String title, @NotNull @Size(max = 5000) String body) {
    }

    private final NoteRepository notes;

    public NoteController(NoteRepository notes) {
        this.notes = notes;
    }

    @GetMapping
    public List<Note> list(@AuthenticationPrincipal AuthenticatedUser user) {
        return notes.findAllByOwner(user.id());
    }

    @PostMapping
    public ResponseEntity<Note> create(@AuthenticationPrincipal AuthenticatedUser user,
                                       @Valid @RequestBody NoteRequest request) {
        // Cualquier "userId" que el cliente añada al JSON se ignora: el record no tiene ese campo.
        return ResponseEntity.status(HttpStatus.CREATED).body(notes.insert(user.id(), request.title(), request.body()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id) {
        if (!notes.deleteByOwner(id, user.id())) {
            // 404 tanto si no existe como si es de otro: no se revela que ese id está en uso por alguien más.
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "NOT_FOUND", "message", "Recurso no encontrado."));
        }
        return ResponseEntity.noContent().build();
    }
}
