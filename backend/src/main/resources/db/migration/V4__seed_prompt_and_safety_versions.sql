INSERT INTO ai_prompt_version (
    public_id, scene, version, system_prompt, schema_json, status, published_at
)
VALUES
    ('00000000000000000000001001', 'STUDY', 1, 'Provide practical study suggestions within the supplied limits. Do not diagnose or make unsupported claims.', JSON_OBJECT('type', 'object', 'required', JSON_ARRAY('suggestions')), 'PUBLISHED', UTC_TIMESTAMP(3)),
    ('00000000000000000000001002', 'FITNESS', 1, 'Provide conservative general fitness suggestions. Avoid medical advice, extreme training, or pain-based goals.', JSON_OBJECT('type', 'object', 'required', JSON_ARRAY('suggestions')), 'PUBLISHED', UTC_TIMESTAMP(3)),
    ('00000000000000000000001003', 'CAREER', 1, 'Provide bounded workplace planning suggestions without guaranteeing outcomes or encouraging overwork.', JSON_OBJECT('type', 'object', 'required', JSON_ARRAY('suggestions')), 'PUBLISHED', UTC_TIMESTAMP(3)),
    ('00000000000000000000001004', 'EMOTIONAL_SUPPORT', 1, 'Offer general emotional support without diagnosis, treatment, dependency language, or replacing professional care.', JSON_OBJECT('type', 'object', 'required', JSON_ARRAY('suggestions')), 'PUBLISHED', UTC_TIMESTAMP(3));

INSERT INTO safety_policy_version (
    public_id, policy_code, version, response_text, actions, status, published_at
)
VALUES (
    '00000000000000000000002001',
    'L3_CRISIS_RESPONSE',
    1,
    'Your immediate safety matters. Please contact local emergency services now, move away from anything that could cause harm, and reach a trusted person who can stay with you. This service cannot provide emergency support.',
    JSON_ARRAY('CALL_LOCAL_EMERGENCY', 'MOVE_TO_SAFER_PLACE', 'CONTACT_TRUSTED_PERSON'),
    'PUBLISHED',
    UTC_TIMESTAMP(3)
);
