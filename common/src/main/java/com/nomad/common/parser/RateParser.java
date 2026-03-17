package com.nomad.common.parser;

import com.nomad.common.dto.CurrencyRate;
import java.util.List;

public interface RateParser {

    List<CurrencyRate> parse(String html);

}