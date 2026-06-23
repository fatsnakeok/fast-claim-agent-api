package com.fastclaim.repository;

import com.fastclaim.entity.PolicyDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PolicyDocumentRepository extends JpaRepository<PolicyDocument, Long> {
}
