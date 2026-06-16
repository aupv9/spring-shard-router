package org.springframework.boot.starter.sharding.migration;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Immutable result of a completed (or failed) shard migration job.
 *
 * <p>Callers should check {@link #isSuccess()} before using row counts — a failed
 * migration may have only partially copied rows. In that case {@link #getRowsCopied()}
 * reflects how many rows were successfully written before the failure.
 */
public final class MigrationResult {

    public enum Status { SUCCESS, PARTIAL_FAILURE, VERIFICATION_FAILED }

    private final Status        status;
    private final long          rowsCopied;
    private final long          rowsDeleted;     // only non-zero when deleteAfterCopy=true
    private final long          rowsVerified;    // only non-zero when verify() was called
    private final Instant       startedAt;
    private final Instant       completedAt;
    private final List<String>  errors;          // empty on SUCCESS

    private MigrationResult(Builder b) {
        this.status      = b.status;
        this.rowsCopied  = b.rowsCopied;
        this.rowsDeleted = b.rowsDeleted;
        this.rowsVerified= b.rowsVerified;
        this.startedAt   = b.startedAt;
        this.completedAt = b.completedAt;
        this.errors      = List.copyOf(b.errors);
    }

    public boolean  isSuccess()       { return status == Status.SUCCESS; }
    public Status   getStatus()       { return status; }
    public long     getRowsCopied()   { return rowsCopied; }
    public long     getRowsDeleted()  { return rowsDeleted; }
    public long     getRowsVerified() { return rowsVerified; }
    public Instant  getStartedAt()    { return startedAt; }
    public Instant  getCompletedAt()  { return completedAt; }
    public List<String> getErrors()   { return errors; }

    public Duration getDuration() {
        return Duration.between(startedAt, completedAt);
    }

    @Override
    public String toString() {
        return "MigrationResult{status=" + status
            + ", rowsCopied=" + rowsCopied
            + ", rowsDeleted=" + rowsDeleted
            + ", duration=" + getDuration()
            + ", errors=" + errors + '}';
    }

    static Builder builder(Instant startedAt) {
        return new Builder(startedAt);
    }

    static final class Builder {
        Status      status      = Status.SUCCESS;
        long        rowsCopied;
        long        rowsDeleted;
        long        rowsVerified;
        final Instant startedAt;
        Instant     completedAt = Instant.now();
        List<String> errors     = List.of();

        Builder(Instant startedAt) { this.startedAt = startedAt; }

        Builder status(Status s)            { this.status = s;        return this; }
        Builder rowsCopied(long n)          { this.rowsCopied = n;    return this; }
        Builder rowsDeleted(long n)         { this.rowsDeleted = n;   return this; }
        Builder rowsVerified(long n)        { this.rowsVerified = n;  return this; }
        Builder completedAt(Instant t)      { this.completedAt = t;   return this; }
        Builder errors(List<String> errs)   { this.errors = errs;     return this; }
        MigrationResult build()             { return new MigrationResult(this); }
    }
}
