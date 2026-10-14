-- Tax sections (product plan 2.1 "Tax tags"): a category can count towards a section (Health insurance -> 80D), and any
-- transaction can be tagged with a section's code ("80C") to count on its own. The FY report adds them up.
create table tax_mappings (
    space_id     uuid         not null references spaces (id),
    category_id  uuid         not null references categories (id),
    section      varchar(16)  not null,
    primary key (space_id, category_id)
);
