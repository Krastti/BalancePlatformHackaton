package com.kratti.dto.response;

/** Четыре непересекающихся перехода решений сравнимых пар. */
public record TransitionMatrix(long approveToApprove, long approveToDecline,
                               long declineToApprove, long declineToDecline) { }
