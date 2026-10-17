package cc.sirrus.ysmlib.cpm;

import java.util.*;

/** Owned source values. Expressions and unknown fields remain inert; numbers retain their source spelling. */
public sealed interface CpmValue {
    record Member(String name,CpmValue value) { public Member { Objects.requireNonNull(name);Objects.requireNonNull(value); } }
    /** fields is the last-member-wins lookup; members retains all occurrences in source order. */
    record ObjectValue(Map<String,CpmValue> fields,List<Member> members) implements CpmValue {
        public ObjectValue { fields=Collections.unmodifiableMap(new LinkedHashMap<>(fields));members=List.copyOf(members); }
        public ObjectValue(Map<String,CpmValue> fields) {
            this(fields,fields.entrySet().stream().map(e->new Member(e.getKey(),e.getValue())).toList());
        }
    }
    record ArrayValue(List<CpmValue> values) implements CpmValue {
        public ArrayValue { values=List.copyOf(values); }
    }
    record StringValue(String value) implements CpmValue { public StringValue { Objects.requireNonNull(value); } }
    record NumberValue(String value) implements CpmValue { public NumberValue { Objects.requireNonNull(value); } }
    record BooleanValue(boolean value) implements CpmValue {}
    enum NullValue implements CpmValue { INSTANCE }
}
