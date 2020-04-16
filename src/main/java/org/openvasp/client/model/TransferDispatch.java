package org.openvasp.client.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

/**
 * @author Olexandr_Bilovol@epam.com
 */
@Getter
@Setter
public final class TransferDispatch extends TransferMessage {

    @JsonProperty("tx")
    private Transaction tx;

    {
        getHeader().setMessageType(TypeDescriptor.TRANSFER_DISPATCH);
    }

}