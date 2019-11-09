package it.at7.gemini.dsl.entities;

import java.util.ArrayList;
import java.util.List;

public class RawEntityBuilder {
    private static final String namePattern = "^[a-zA-Z0-9_]{3,}$";

    private String name;
    private boolean embedable = false;
    private boolean oneRecord = false;
    private boolean tree;
    private List<RawEntity.Entry> entries = new ArrayList<>();
    private List<String> implementsIntefaces = new ArrayList<>();

    public RawEntityBuilder addName(String name) {
        if (!name.matches(namePattern)) {
            throw new RuntimeException(String.format("name %s doesn't match regexp %s", name, namePattern));
        }
        this.name = name.toUpperCase();
        return this;
    }

    public RawEntityBuilder addEntry(RawEntity.Entry entry) {
        entries.add(entry);
        return this;
    }

    public RawEntityBuilder addImplementsInterface(String implementsName) {
        implementsIntefaces.add(implementsName);
        return this;
    }

    public RawEntityBuilder isEmbedable() {
        this.embedable = true;
        return this;
    }

    public RawEntityBuilder isOneRec() {
        assert !this.embedable;
        this.oneRecord = true;
        return this;
    }

    public RawEntityBuilder isTree() {
        this.tree = true;
        return this;
    }

    public String getName() {
        return name;
    }

    public boolean getIsOneRec() {
        return this.oneRecord;
    }

    public RawEntity build() {
        return new RawEntity(name, embedable, oneRecord, tree, entries, implementsIntefaces);
    }

    public static class EntryBuilder {
        private final RawEntityBuilder entityBuilder;
        private String type;
        private String name;
        private boolean isLogicalKey;
        private int lkOrder;

        public EntryBuilder(RawEntityBuilder entityBuilder, String type, String name) {
            this.entityBuilder = entityBuilder;
            this.type = type;
            if (!name.matches(namePattern)) {
                throw new RuntimeException(String.format("name %s doesn't match regexp ^[a-zA-Z0-9_]{3,}$", name));
            }
            this.name = name;
            this.isLogicalKey = false;
            this.lkOrder = 0;
        }

        public RawEntityBuilder getEntityBuilder() {
            return entityBuilder;
        }

        public void isLogicalKey(int lkOrder) {
            this.isLogicalKey = true;
            this.lkOrder = lkOrder;
        }

        public RawEntity.Entry build() {
            return new RawEntity.Entry(type, name, isLogicalKey, lkOrder);
        }
    }
}
