package org.openridesync.app

import io.grpc.Status
import org.junit.Assert.assertEquals
import org.junit.Test
import org.openridesync.app.honda.HondaSessionRead
import org.openridesync.diagnostics.DiagnosticJournal.ErrorCode
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException

class HondaSessionReadTest {
    @Test fun sharedErrorsPreserveCanonicalStatusWithoutProviderDescriptions() {
        val denied = Status.PERMISSION_DENIED.withDescription("synthetic private provider payload").asRuntimeException()
        val notFound = Status.NOT_FOUND.withDescription("synthetic account identity").asRuntimeException()
        assertEquals("PERMISSION_DENIED", HondaSessionRead.errorCode(ExecutionException(denied)))
        assertEquals(ErrorCode.PERMISSION_DENIED, HondaSessionRead.diagnosticError(HondaSessionRead.errorCode(denied)))
        assertEquals(ErrorCode.NOT_FOUND, HondaSessionRead.diagnosticError(HondaSessionRead.errorCode(notFound)))
        assertEquals(ErrorCode.TIMEOUT, HondaSessionRead.diagnosticError(HondaSessionRead.errorCode(TimeoutException("synthetic detail"))))
        assertEquals(ErrorCode.TIMEOUT, HondaSessionRead.diagnosticError(HondaSessionRead.errorCode(java.net.SocketTimeoutException("synthetic detail"))))
        assertEquals("CANCELLED", HondaSessionRead.errorCode(InterruptedException("synthetic detail")))
        assertEquals("UNAVAILABLE", HondaSessionRead.errorCode(IllegalStateException("synthetic credential")))
        assertEquals(ErrorCode.UNKNOWN, HondaSessionRead.diagnosticError("synthetic arbitrary text"))
    }
}
