package com.migration.model;

public class Recipe {
    private String id;
    private String name;
    private String description;
    private String category;
    private boolean required;

    public Recipe(String id, String name, String description, String category, boolean required) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.category = category;
        this.required = required;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getCategory() { return category; }
    public boolean isRequired() { return required; }
}
