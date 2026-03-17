package com.nomad.loom.controller;

import com.nomad.common.dto.DeltaResponse;
import com.nomad.loom.service.LoomCurrencyService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/loom")
public class LoomCurrencyController {

    private final LoomCurrencyService loomCurrencyService;

    @GetMapping
    public LoomCurrencyService.CollectionResult getAllRates(){
        try {
            return loomCurrencyService.collectAndSaveRate();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @GetMapping("/{code}")
    public DeltaResponse getDelta(@PathVariable String code){
        return loomCurrencyService.calculateDelta(code);
    }
}
