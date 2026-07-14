export const GENERATED_SUMMARY_JSON_SCHEMA = {
  type: "object",
  additionalProperties: false,
  required: ["headline", "overview", "highlights", "progress", "open_loops"],
  properties: {
    headline: { type: "string", maxLength: 80 },
    overview: { type: "string", maxLength: 800 },
    highlights: { $ref: "#/$defs/items" },
    progress: { $ref: "#/$defs/items" },
    open_loops: { $ref: "#/$defs/items" },
  },
  $defs: {
    item: {
      type: "object",
      additionalProperties: false,
      required: ["text", "event_ids"],
      properties: {
        text: { type: "string", minLength: 1, maxLength: 240 },
        event_ids: {
          type: "array",
          minItems: 1,
          maxItems: 8,
          items: { type: "string", pattern: "^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$" },
        },
      },
    },
    items: {
      type: "array",
      maxItems: 8,
      items: { $ref: "#/$defs/item" },
    },
  },
} as const;
