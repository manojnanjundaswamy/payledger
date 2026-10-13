-- Category management (create / rename). Names are unique per space and kind, ignoring case, so a
-- name always means one category: in the transaction form, in reports and when importing a sheet
-- that names categories. Only the seeded defaults exist so far, and they are already unique.
create unique index ux_categories_name on categories (space_id, kind, lower(name));
