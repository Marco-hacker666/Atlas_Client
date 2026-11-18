package atlas.util;

import java.util.Locale;

public final class TuneExperiment {
    public enum State {
        BASELINE_1, CANDIDATE_1, BASELINE_2, CANDIDATE_2, COMMITTED, REJECTED, EXPIRED;

        public boolean finished() {
            return this == COMMITTED || this == REJECTED || this == EXPIRED;
        }
    }

    public final int id;
    public final String label;
    public final double current;
    public final double candidate;
    private final double margin;
    private final int maxRetries;

    private State state = State.BASELINE_1;
    private final double[] baseScores = new double[2];
    private final double[] candidateScores = new double[2];
    private final int[] baseSwings = new int[2];
    private final int[] candidateSwings = new int[2];
    private int retries;
    private String reason = "";

    public TuneExperiment(int id, String label, double current, double candidate, double margin, int maxRetries) {
        this.id = id;
        this.label = label;
        this.current = current;
        this.candidate = candidate;
        this.margin = margin;
        this.maxRetries = maxRetries;
    }

    public State state() {
        return this.state;
    }

    public boolean finished() {
        return this.state.finished();
    }

    public boolean candidateBlock() {
        return this.state == State.CANDIDATE_1 || this.state == State.CANDIDATE_2;
    }

    public double valueNow() {
        return candidateBlock() ? this.candidate : this.current;
    }

    public String reason() {
        return this.reason;
    }

    public double difference(int pair) {
        return this.candidateSwings[pair] > 0 ? this.candidateScores[pair] - this.baseScores[pair] : Double.NaN;
    }

    public State blockScored(double score, int swings) {
        switch (this.state) {
            case BASELINE_1:
                this.baseScores[0] = score;
                this.baseSwings[0] = swings;
                this.state = State.CANDIDATE_1;
                break;
            case CANDIDATE_1:
                this.candidateScores[0] = score;
                this.candidateSwings[0] = swings;
                double first = score - this.baseScores[0];
                if (first <= -this.margin) {
                    this.state = State.REJECTED;
                    this.reason = String.format(Locale.ROOT, "worse by %.1f on the first pair", -first);
                } else {
                    this.state = State.BASELINE_2;
                }
                break;
            case BASELINE_2:
                this.baseScores[1] = score;
                this.baseSwings[1] = swings;
                this.state = State.CANDIDATE_2;
                break;
            case CANDIDATE_2:
                this.candidateScores[1] = score;
                this.candidateSwings[1] = swings;
                double d1 = this.candidateScores[0] - this.baseScores[0];
                double d2 = this.candidateScores[1] - this.baseScores[1];
                double mean = (d1 + d2) / 2.0;
                if (d1 > 0.0 && d2 > 0.0 && mean >= this.margin) {
                    this.state = State.COMMITTED;
                    this.reason = String.format(Locale.ROOT, "better both times (%+.1f, %+.1f), mean %+.1f >= %.1f",
                            d1, d2, mean, this.margin);
                } else {
                    this.state = State.REJECTED;
                    this.reason = String.format(Locale.ROOT, "not better both times by the margin (%+.1f, %+.1f, mean %+.1f, needs %.1f)",
                            d1, d2, mean, this.margin);
                }
                break;
            default:
                break;
        }
        return this.state;
    }

    public State blockThin() {
        if (this.state.finished()) {
            return this.state;
        }
        this.retries++;
        if (this.retries > this.maxRetries) {
            this.state = State.EXPIRED;
            this.reason = "not enough fighting to measure (" + this.maxRetries + " blocks replayed)";
        }
        return this.state;
    }

    public State expire(String why) {
        if (!this.state.finished()) {
            this.state = State.EXPIRED;
            this.reason = why;
        }
        return this.state;
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "#%d %s %.3f -> %.3f [%s]", this.id, this.label, this.current,
                this.candidate, this.state);
    }
}
