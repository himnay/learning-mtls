package com.org.mtls.producer.repository;

import com.org.mtls.producer.entites.GreetingTemplate;
import org.springframework.data.repository.ListCrudRepository;

public interface GreetingTemplateRepository extends ListCrudRepository<GreetingTemplate, String> {}
