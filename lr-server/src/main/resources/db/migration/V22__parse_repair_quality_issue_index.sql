ALTER TABLE `lr_data_quality_issue`
  ADD KEY `idx_quality_parse_repair` (`tenant_id`, `issue_type`, `ref_type`, `status`, `id`);
