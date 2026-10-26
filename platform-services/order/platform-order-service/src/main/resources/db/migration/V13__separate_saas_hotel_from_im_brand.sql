-- OpenChat is the IM product; the demo hotel belongs to the SaaS demo tenant.
UPDATE htl_hotel
SET name = 'SaaS Hotel'
WHERE name = 'OpenChat Hotel';
