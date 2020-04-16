package org.openvasp.client.config;

import lombok.val;
import org.bouncycastle.util.encoders.Base64;
import org.openvasp.client.common.Json;
import org.openvasp.client.model.InfuraConfig;

import static org.openvasp.client.common.TestConstants.*;

/**
 * @author Olexandr_Bilovol@epam.com
 */
public class RopstenTestModule extends VaspModule {

    public static final InfuraConfig infuraConfig;
    public static final RopstenTestModule module1;
    public static final RopstenTestModule module2;
    public static final RopstenTestModule module3;

    static {
        infuraConfig = Json.loadTestJson(InfuraConfig.class, "infura-config.json");
        module1 = new RopstenTestModule(Json.loadTestJson(VaspConfig.class, VASP_CONFIG_ROPSTEN_1));
        module2 = new RopstenTestModule(Json.loadTestJson(VaspConfig.class, VASP_CONFIG_ROPSTEN_2));
        module3 = new RopstenTestModule(Json.loadTestJson(VaspConfig.class, VASP_CONFIG_ROPSTEN_3));
    }

    public RopstenTestModule(final VaspConfig vaspConfig) {
        super(vaspConfig);
        val infuraSecret = ":" + infuraConfig.getSecret();
        val auth = Base64.toBase64String(infuraSecret.getBytes());
        contractHttpService.addHeader("Authorization", "Basic " + auth);
    }

}