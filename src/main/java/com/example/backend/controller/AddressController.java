package com.example.backend.controller;

import com.example.backend.dto.AddressRequest;
import com.example.backend.dto.AddressResponse;
import com.example.backend.service.AddressService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/addresses")
public class AddressController {

    private final AddressService addressService;

    public AddressController(AddressService addressService) {
        this.addressService = addressService;
    }

    @GetMapping
    public List<AddressResponse> list(@AuthenticationPrincipal UserDetails userDetails) {
        return addressService.list(userDetails.getUsername());
    }

    @PostMapping
    public ResponseEntity<AddressResponse> create(@AuthenticationPrincipal UserDetails userDetails,
                                                    @Valid @RequestBody AddressRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(addressService.create(userDetails.getUsername(), request));
    }

    @PutMapping("/{id}")
    public AddressResponse update(@AuthenticationPrincipal UserDetails userDetails,
                                   @PathVariable Long id, @Valid @RequestBody AddressRequest request) {
        return addressService.update(userDetails.getUsername(), id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal UserDetails userDetails, @PathVariable Long id) {
        addressService.delete(userDetails.getUsername(), id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/default")
    public AddressResponse setDefault(@AuthenticationPrincipal UserDetails userDetails, @PathVariable Long id) {
        return addressService.setDefault(userDetails.getUsername(), id);
    }
}
