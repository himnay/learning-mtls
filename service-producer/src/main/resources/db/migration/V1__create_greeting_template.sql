CREATE TABLE greeting_template
(
    language_code VARCHAR(8)   NOT NULL,
    template      VARCHAR(255) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_greeting_template PRIMARY KEY (language_code),
    CONSTRAINT ck_greeting_template_placeholder CHECK (template LIKE '%\%s%')
);

COMMENT ON TABLE greeting_template IS 'Localised greeting patterns served by service-producer';
COMMENT ON COLUMN greeting_template.template IS 'java.lang.String#formatted pattern with exactly one %s for the name';
