package com.superskidder.kylinops.backend.agentruntime.security;

import java.security.PrivateKey;
import java.security.PublicKey;

public interface ApprovalKeyLoader {
    PrivateKey loadPrivateKey(String reference);
    PublicKey loadPublicKey(String reference);
}
