package com.moataz.paymentwallet.funding;

import com.moataz.paymentwallet.common.error.BusinessRuleException;
import com.moataz.paymentwallet.funding.dto.ProviderSummary;
import com.moataz.paymentwallet.funding.provider.PaymentProviderAdapter;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class ProviderRegistry {

    private final Map<PaymentProvider, PaymentProviderAdapter> adapters =
            new EnumMap<>(PaymentProvider.class);

    public ProviderRegistry(List<PaymentProviderAdapter> discovered) {
        discovered.forEach(adapter -> adapters.put(adapter.provider(), adapter));
    }

    public PaymentProviderAdapter require(PaymentProvider provider, PaymentDirection direction) {
        PaymentProviderAdapter adapter = adapters.get(provider);
        if (adapter == null) {
            throw new BusinessRuleException("No adapter for " + provider);
        }
        if (!adapter.isConfigured()) {
            throw new BusinessRuleException(provider + " is not configured on this deployment");
        }
        if (!adapter.supports(direction)) {
            throw new BusinessRuleException(provider + " cannot handle a "
                    + direction.name().toLowerCase());
        }
        return adapter;
    }

    /** Webhooks are read whether or not the provider is configured for outbound calls. */
    public PaymentProviderAdapter get(PaymentProvider provider) {
        PaymentProviderAdapter adapter = adapters.get(provider);
        if (adapter == null) {
            throw new BusinessRuleException("No adapter for " + provider);
        }
        return adapter;
    }

    public List<ProviderSummary> summaries() {
        return adapters.values().stream()
                .map(a -> new ProviderSummary(a.provider(), a.isConfigured(), a.supports()))
                .sorted(Comparator.comparing(ProviderSummary::provider))
                .toList();
    }

    public List<ProviderSummary> available(PaymentDirection direction) {
        return summaries().stream()
                .filter(ProviderSummary::configured)
                .filter(s -> s.supports().contains(direction))
                .toList();
    }
}
