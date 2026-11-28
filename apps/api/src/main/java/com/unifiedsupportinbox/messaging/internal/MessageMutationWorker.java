package com.unifiedsupportinbox.messaging.internal;

import com.unifiedsupportinbox.messaging.MessageDeliveryProvider;
import com.unifiedsupportinbox.messaging.MessageDeliveryProvider.*;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
class MessageMutationWorker {
    private final MessageMutationService mutations;
    private final ObjectProvider<MessageDeliveryProvider> providers;
    MessageMutationWorker(MessageMutationService mutations,ObjectProvider<MessageDeliveryProvider> providers){this.mutations=mutations;this.providers=providers;}
    void process(UUID id) {
        var claim=mutations.claim(id);
        if(claim==null)return;
        var row=claim.message();
        var matching=providers.orderedStream().filter(p->p.supports(row.provider())).toList();
        MutationResult result;
        try {
            result=matching.size()==1?matching.getFirst().mutate(new MutationCommand(id,row.id(),row.integration(),row.provider(),row.channel(),claim.action(),claim.body(),row.format(),row.target()))
                    :MutationResult.failed("PROVIDER_UNAVAILABLE");
        } catch(RuntimeException failure) {
            // An exception after dispatch is not evidence that nothing happened remotely.
            result=MutationResult.uncertain("MUTATION_OUTCOME_UNKNOWN");
        }
        mutations.finish(claim,result);
    }
}
