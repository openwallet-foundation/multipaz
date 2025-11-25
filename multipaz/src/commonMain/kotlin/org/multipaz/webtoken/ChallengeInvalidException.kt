package org.multipaz.webtoken

/** Thrown when `challenge` or `nonce` claim in a web token is not valid or missing. */
class ChallengeInvalidException(): Exception("Challenge is missing or not valid")