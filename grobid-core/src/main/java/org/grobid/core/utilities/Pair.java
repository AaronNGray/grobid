package org.grobid.core.utilities;

public class Pair<L, R> /*extends org.apache.commons.lang3.tuple.Pair*/ {

    public final L a;
    public final R b;

    public Pair(L a, R b) {
        this.a = a;
        this.b = b;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb
                .append("('")
                .append(a)
                .append("'; '")
                .append(b)
                .append("')");
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Pair)) {
            return false;
        }
        Pair<?, ?> that = (Pair<?, ?>) o;
        return ((this.a == null) ? that.a == null : this.a.equals(that.a)) &&
                ((this.b == null) ? that.b == null : this.b.equals(that.b));
    }

    @Override
    public int hashCode() {
        return 7 * (a != null ? a.hashCode() : 11) + 13 * (b != null ? b.hashCode() : 3);
    }

    public L getA() {
        return a;
    }

    public R getB() {
        return b;
    }

    public L getLeft() {
        return a;
    }

    public R getRight() {
        return b;
    }

    public static <L,R> Pair<L,R> of(L left, R right) {
        return new Pair(left, right);
    }
}
