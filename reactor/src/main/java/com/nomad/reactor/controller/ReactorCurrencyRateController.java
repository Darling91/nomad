package com.nomad.reactor.controller;

import com.nomad.common.dto.DeltaResponse;
import com.nomad.reactor.service.ReactorCurrencyService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/rates")
public class ReactorCurrencyRateController {

    private final ReactorCurrencyService reactorCurrencyService;

    @GetMapping
    public Mono<ReactorCurrencyService.CollectionResult> getAllRates(){
        return reactorCurrencyService.collectAndSaveRates();
    }

    @GetMapping("/{code}")
    public Mono<DeltaResponse> getDelta(@PathVariable String code){
        return reactorCurrencyService.calculateDelta(code);
    }
}
